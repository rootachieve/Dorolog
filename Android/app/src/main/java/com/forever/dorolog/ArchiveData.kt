package com.forever.dorolog

import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

enum class ArchiveTab { Trips, Map }

enum class ArchiveSort(val label: String) {
    Latest("최신순"), Oldest("오래된순"),
}

data class ArchiveTrip(
    val id: String,
    val departureName: String,
    val destinationName: String?,
    val destinationLatitude: Double?,
    val destinationLongitude: Double?,
    val startedAt: Instant,
    val storyCount: Int,
) {
    val date: LocalDate get() = startedAt.atZone(ZoneId.systemDefault()).toLocalDate()
}

data class ArchiveRegion(
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
    val storyCount: Int,
) {
    val id: String get() = name
}

data class ArchiveStory(
    val id: String,
    val tripId: String,
    val title: String,
    val region: String,
    val latitude: Double?,
    val longitude: Double?,
    val summary: String,
    val highlights: List<String>,
    val recordedAt: Instant?,
) {
    val date: LocalDate? get() = recordedAt?.atZone(ZoneId.systemDefault())?.toLocalDate()
}

data class ArchiveContent(
    val trips: List<ArchiveTrip> = emptyList(),
    val regions: List<ArchiveRegion> = emptyList(),
    val stories: List<ArchiveStory> = emptyList(),
    val tripCount: Int = 0,
    val storyCount: Int = 0,
    val isSample: Boolean = false,
)

data class ArchiveTripDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("originName") val originName: String? = null,
    @SerializedName("originLatitude") val originLatitude: Double? = null,
    @SerializedName("originLongitude") val originLongitude: Double? = null,
    @SerializedName("destinationName") val destinationName: String? = null,
    @SerializedName("destinationLatitude") val destinationLatitude: Double? = null,
    @SerializedName("destinationLongitude") val destinationLongitude: Double? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("startedAt") val startedAt: String? = null,
    @SerializedName("endedAt") val endedAt: String? = null,
    @SerializedName("storyCount") val storyCount: Int? = null,
)

data class ArchiveResponseDto(
    @SerializedName("trips") val trips: List<ArchiveTripDto?>? = null,
) {
    fun toModel(): List<ArchiveTrip> {
        val items = requireNotNull(trips).map { item ->
            val trip = requireNotNull(item)
            validateArchiveCoordinates(trip.originLatitude, trip.originLongitude)
            validateArchiveCoordinates(trip.destinationLatitude, trip.destinationLongitude)
            require(trip.status == "active" || trip.status == "finished")
            trip.endedAt?.let(Instant::parse)
            ArchiveTrip(
                id = requireNotNull(trip.id).also { require(it.isNotBlank()) },
                departureName = requireNotNull(trip.originName).trim().also { require(it.isNotEmpty()) },
                destinationName = trip.destinationName?.trim()?.also { require(it.isNotEmpty()) },
                destinationLatitude = trip.destinationLatitude,
                destinationLongitude = trip.destinationLongitude,
                startedAt = Instant.parse(requireNotNull(trip.startedAt)),
                storyCount = requireNotNull(trip.storyCount).also { require(it >= 0) },
            )
        }
        require(items.map { it.id }.distinct().size == items.size)
        return items
    }
}

data class ArchiveRegionDto(
    @SerializedName("region") val region: String? = null,
    @SerializedName("storyCount") val storyCount: Int? = null,
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null,
)

data class ArchiveMapStoryDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("tripId") val tripId: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("region") val region: String? = null,
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null,
    @SerializedName("summary") val summary: String? = null,
    @SerializedName("highlights") val highlights: List<String?>? = null,
    @SerializedName("recordedAt") val recordedAt: String? = null,
)

data class ArchiveMapResponseDto(
    @SerializedName("storyCount") val storyCount: Int? = null,
    @SerializedName("tripCount") val tripCount: Int? = null,
    @SerializedName("regions") val regions: List<ArchiveRegionDto?>? = null,
    @SerializedName("stories") val stories: List<ArchiveMapStoryDto?>? = null,
) {
    fun toModel(trips: List<ArchiveTrip>, isSample: Boolean = false): ArchiveContent {
        val items = requireNotNull(stories).map { item ->
            val story = requireNotNull(item)
            validateArchiveCoordinates(story.latitude, story.longitude)
            ArchiveStory(
                id = requireNotNull(story.id).also { require(it.isNotBlank()) },
                tripId = requireNotNull(story.tripId).also { require(it.isNotBlank()) },
                title = requireNotNull(story.title).trim().also { require(it.isNotEmpty()) },
                region = requireNotNull(story.region).trim().also { require(it.isNotEmpty()) },
                latitude = story.latitude,
                longitude = story.longitude,
                summary = requireNotNull(story.summary),
                highlights = requireNotNull(story.highlights).map { requireNotNull(it) },
                recordedAt = story.recordedAt?.let(Instant::parse),
            )
        }
        require(items.map { it.tripId to it.id }.distinct().size == items.size)
        val places = requireNotNull(regions).map { item ->
            val region = requireNotNull(item)
            validateArchiveCoordinates(region.latitude, region.longitude)
            ArchiveRegion(
                name = requireNotNull(region.region).trim().also { require(it.isNotEmpty()) },
                latitude = region.latitude,
                longitude = region.longitude,
                storyCount = requireNotNull(region.storyCount).also { require(it > 0) },
            )
        }
        require(places.map { it.id }.distinct().size == places.size)
        require(storyCount == items.size && tripCount == items.map { it.tripId }.distinct().size)
        require(places.associate { it.name to it.storyCount } == items.groupingBy { it.region }.eachCount())
        return ArchiveContent(trips, places, items, requireNotNull(tripCount), requireNotNull(storyCount), isSample)
    }
}

private fun validateArchiveCoordinates(latitude: Double?, longitude: Double?) {
    require((latitude == null) == (longitude == null))
    latitude?.let { require(it.isFinite() && it in -90.0..90.0) }
    longitude?.let { require(it.isFinite() && it in -180.0..180.0) }
}

interface ArchiveApi {
    @GET("trips")
    suspend fun trips(@Query("limit") limit: Int): ArchiveResponseDto

    @GET("stories/map")
    suspend fun map(): ArchiveMapResponseDto
}

class ArchiveRepository(
    private val loadTrips: suspend () -> ArchiveResponseDto = { defaultArchiveApi.trips(50) },
    private val loadMap: suspend () -> ArchiveMapResponseDto = { defaultArchiveApi.map() },
    private val isSample: Boolean = false,
) {
    suspend fun load(): ArchiveContent = loadMap().toModel(loadTrips().toModel(), isSample)

    companion object {
        fun remote(baseUrl: String): ArchiveRepository {
            val api = archiveApi(baseUrl)
            return ArchiveRepository({ api.trips(50) }, api::map)
        }
    }
}

private val defaultArchiveApi by lazy {
    if (BuildConfig.TRIP_API_BASE_URL.isBlank()) throw IOException("여행 서버 주소가 설정되지 않았어요")
    archiveApi(BuildConfig.TRIP_API_BASE_URL)
}

private fun archiveApi(baseUrl: String): ArchiveApi {
    val uri = URI(baseUrl)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.query == null && uri.fragment == null && baseUrl.endsWith('/'))
    return Retrofit.Builder().baseUrl(baseUrl)
        .addConverterFactory(GsonConverterFactory.create()).build().create(ArchiveApi::class.java)
}

// Explicit preview fixtures; the default repository never substitutes these for server data.
private val sampleArchiveEntries = listOf(
    sampleArchiveTrip("sample-busan-2", "바다를 보며 나눈 이야기", "광주 용봉동", "부산 해운대", "부산", 35.1587, 129.1604, "2026-11-11", 5),
    sampleArchiveTrip("sample-gangneung-2", "가족과 함께한 가을 바다", "서울특별시", "강릉 경포해변", "강릉시", 37.8047, 128.9092, "2026-11-08", 4),
    sampleArchiveTrip("sample-seoul-2", "오랜만에 만난 서울", "광주 용봉동", "서울특별시", "서울", 37.5665, 126.9780, "2026-11-03", 3),
    sampleArchiveTrip("sample-jeonju", "한옥 골목에서 보낸 하루", "대전광역시", "전주 한옥마을", "전주시", 35.8144, 127.1526, "2026-10-28", 5),
    sampleArchiveTrip("sample-daegu", "할머니를 만나러 가는 길", "부산광역시", "대구광역시", "대구", 35.8714, 128.6014, "2026-10-18", 4),
    sampleArchiveTrip("sample-daejeon", "우리 가족의 작은 나들이", "서울특별시", "대전광역시", "대전", 36.3504, 127.3845, "2026-10-11", 3),
    sampleArchiveTrip("sample-cheonan", "함께 떠난 주말 여행", "수원시", "천안시", "천안시", 36.8151, 127.1139, "2026-10-03", 4),
    sampleArchiveTrip("sample-suwon-2", "가을빛을 따라 걷다", "서울특별시", "수원 화성", "수원시", 37.2884, 127.0142, "2026-09-27", 3),
    sampleArchiveTrip("sample-busan-1", "다시 찾은 부산", "광주 용봉동", "부산 광안리", "부산", 35.1532, 129.1186, "2026-09-19", 5),
    sampleArchiveTrip("sample-gangneung-1", "여름의 마지막 바다", "서울특별시", "강릉 안목해변", "강릉시", 37.7711, 128.9473, "2026-08-30", 4),
    sampleArchiveTrip("sample-seoul-1", "처음 함께한 서울 여행", "광주 용봉동", "서울 남산", "서울", 37.5512, 126.9882, "2026-08-16", 3),
    sampleArchiveTrip("sample-suwon-1", "추억을 찾아 떠난 길", "대전광역시", "수원시", "수원시", 37.2636, 127.0286, "2026-08-02", 4),
    sampleArchiveTrip("sample-jeju", "우리의 첫 제주 여행", "제주시 연동", "서귀포시", "서귀포시", 33.2541, 126.5601, "2026-07-20", 4),
)

internal val sampleArchiveResponse = ArchiveResponseDto(sampleArchiveEntries.map { it.first })
internal val sampleArchiveMapResponse: ArchiveMapResponseDto = run {
    val stories = sampleArchiveEntries.flatMap { it.second }
    ArchiveMapResponseDto(stories.size, sampleArchiveEntries.size,
        stories.groupBy { requireNotNull(it.region) }.map { (region, items) ->
            ArchiveRegionDto(region, items.size, items.map { requireNotNull(it.latitude) }.average(),
                items.map { requireNotNull(it.longitude) }.average())
        }, stories)
}
internal val sampleArchiveContent get() = sampleArchiveMapResponse.toModel(sampleArchiveResponse.toModel(), isSample = true)

private fun sampleArchiveTrip(
    id: String, title: String, departure: String, destination: String,
    regionName: String, latitude: Double, longitude: Double, date: String, stories: Int,
): Pair<ArchiveTripDto, List<ArchiveMapStoryDto>> {
    val recordedAt = "${date}T12:00:00+09:00"
    return ArchiveTripDto(id, departure, null, null, destination, latitude, longitude,
        "finished", recordedAt, recordedAt, stories) to List(stories) { index ->
        ArchiveMapStoryDto("$id-story-$index", id, title, regionName, latitude, longitude,
            "가족과 함께 나눈 이야기", emptyList(), recordedAt)
    }
}
