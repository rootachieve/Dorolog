package com.forever.dorolog

import com.google.gson.annotations.SerializedName
import java.net.URI
import java.time.LocalDate
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

enum class ArchiveTab { Trips, Map }

enum class ArchiveSort(val queryValue: String, val label: String) {
    Latest("latest", "최신순"), Oldest("oldest", "오래된순"),
}

data class ArchiveTrip(
    val id: String,
    val title: String,
    val departureName: String,
    val destinationName: String,
    val destinationLatitude: Double,
    val destinationLongitude: Double,
    val regionId: String,
    val regionName: String,
    val date: LocalDate,
    val storyCount: Int,
)

data class ArchiveRegion(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val tripCount: Int,
)

data class ArchiveContent(
    val trips: List<ArchiveTrip> = emptyList(),
    val regions: List<ArchiveRegion> = emptyList(),
    val tripCount: Int = 0,
    val storyCount: Int = 0,
    val isSample: Boolean = false,
)

data class ArchiveDestinationDto(
    @SerializedName("name") val name: String? = null,
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null,
    @SerializedName("regionId") val regionId: String? = null,
    @SerializedName("regionName") val regionName: String? = null,
)

data class ArchiveTripDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("departureName") val departureName: String? = null,
    @SerializedName("destinations") val destinations: List<ArchiveDestinationDto?>? = null,
    @SerializedName("date") val date: String? = null,
    @SerializedName("storyCount") val storyCount: Int? = null,
)

data class ArchiveResponseDto(
    @SerializedName("trips") val trips: List<ArchiveTripDto?>? = null,
) {
    fun toModel(isSample: Boolean = false): ArchiveContent {
        val items = requireNotNull(trips).map { item ->
            val trip = requireNotNull(item)
            val id = requireNotNull(trip.id).also {
                require(it.isNotBlank())
            }
            val departure = requireNotNull(trip.departureName).trim().also { require(it.isNotEmpty()) }
            val destination = requireNotNull(trip.destinations?.lastOrNull())
            val destinationName = requireNotNull(destination.name).trim().also { require(it.isNotEmpty()) }
            val latitude = requireNotNull(destination.latitude).also { require(it.isFinite() && it in -90.0..90.0) }
            val longitude = requireNotNull(destination.longitude).also { require(it.isFinite() && it in -180.0..180.0) }
            ArchiveTrip(
                id = id,
                title = trip.title?.trim()?.takeIf { it.isNotEmpty() } ?: "$departure → $destinationName",
                departureName = departure,
                destinationName = destinationName,
                destinationLatitude = latitude,
                destinationLongitude = longitude,
                regionId = requireNotNull(destination.regionId).trim().also { require(it.isNotEmpty()) },
                regionName = requireNotNull(destination.regionName).trim().also { require(it.isNotEmpty()) },
                date = LocalDate.parse(requireNotNull(trip.date)),
                storyCount = requireNotNull(trip.storyCount).also { require(it >= 0) },
            )
        }
        require(items.map { it.id }.distinct().size == items.size)
        val regions = items.groupBy { it.regionId }.map { (id, trips) ->
            require(trips.map { it.regionName }.distinct().size == 1)
            ArchiveRegion(
                id = id,
                name = trips.first().regionName,
                latitude = trips.map { it.destinationLatitude }.average(),
                longitude = trips.map { it.destinationLongitude }.average(),
                tripCount = trips.size,
            )
        }.sortedBy { it.id }
        val storyCount = items.sumOf { it.storyCount.toLong() }
        require(storyCount <= Int.MAX_VALUE)
        return ArchiveContent(items, regions, items.size, storyCount.toInt(), isSample)
    }
}

interface ArchiveApi {
    @GET("trips")
    suspend fun trips(@Query("sort") sort: String): ArchiveResponseDto
}

class ArchiveRepository(
    private val loadResponse: suspend (ArchiveSort) -> ArchiveResponseDto = defaultArchiveLoader(),
    private val isSample: Boolean = BuildConfig.TRIP_API_BASE_URL.isBlank(),
) {
    suspend fun load(sort: ArchiveSort): ArchiveContent = loadResponse(sort).toModel(isSample)

    companion object {
        fun remote(baseUrl: String): ArchiveRepository = ArchiveRepository(remoteArchiveLoader(baseUrl), false)
    }
}

private fun defaultArchiveLoader(): suspend (ArchiveSort) -> ArchiveResponseDto =
    if (BuildConfig.TRIP_API_BASE_URL.isBlank()) {
        { sort ->
            val trips = requireNotNull(sampleArchiveResponse.trips)
            ArchiveResponseDto(if (sort == ArchiveSort.Latest) trips.sortedByDescending { it?.date } else trips.sortedBy { it?.date })
        }
    } else remoteArchiveLoader(BuildConfig.TRIP_API_BASE_URL)

private fun remoteArchiveLoader(baseUrl: String): suspend (ArchiveSort) -> ArchiveResponseDto {
    val uri = URI(baseUrl)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.query == null && uri.fragment == null && baseUrl.endsWith('/'))
    val api = Retrofit.Builder().baseUrl(baseUrl)
        .addConverterFactory(GsonConverterFactory.create()).build().create(ArchiveApi::class.java)
    return { sort -> api.trips(sort.queryValue) }
}

// shortcut: 서버 계약 확정 전에는 샘플임을 표시한 여행을 공급하며, API 주소 설정 시 원격 데이터로 교체한다.
internal val sampleArchiveResponse = ArchiveResponseDto(listOf(
    sampleArchiveTrip("sample-busan-2", "바다를 보며 나눈 이야기", "광주 용봉동", "부산 해운대", "26000", "부산", 35.1587, 129.1604, "2026-11-11", 5),
    sampleArchiveTrip("sample-gangneung-2", "가족과 함께한 가을 바다", "서울특별시", "강릉 경포해변", "42150", "강릉시", 37.8047, 128.9092, "2026-11-08", 4),
    sampleArchiveTrip("sample-seoul-2", "오랜만에 만난 서울", "광주 용봉동", "서울특별시", "11000", "서울", 37.5665, 126.9780, "2026-11-03", 3),
    sampleArchiveTrip("sample-jeonju", "한옥 골목에서 보낸 하루", "대전광역시", "전주 한옥마을", "45110", "전주시", 35.8144, 127.1526, "2026-10-28", 5),
    sampleArchiveTrip("sample-daegu", "할머니를 만나러 가는 길", "부산광역시", "대구광역시", "27000", "대구", 35.8714, 128.6014, "2026-10-18", 4),
    sampleArchiveTrip("sample-daejeon", "우리 가족의 작은 나들이", "서울특별시", "대전광역시", "30000", "대전", 36.3504, 127.3845, "2026-10-11", 3),
    sampleArchiveTrip("sample-cheonan", "함께 떠난 주말 여행", "수원시", "천안시", "44130", "천안시", 36.8151, 127.1139, "2026-10-03", 4),
    sampleArchiveTrip("sample-suwon-2", "가을빛을 따라 걷다", "서울특별시", "수원 화성", "41110", "수원시", 37.2884, 127.0142, "2026-09-27", 3),
    sampleArchiveTrip("sample-busan-1", "다시 찾은 부산", "광주 용봉동", "부산 광안리", "26000", "부산", 35.1532, 129.1186, "2026-09-19", 5),
    sampleArchiveTrip("sample-gangneung-1", "여름의 마지막 바다", "서울특별시", "강릉 안목해변", "42150", "강릉시", 37.7711, 128.9473, "2026-08-30", 4),
    sampleArchiveTrip("sample-seoul-1", "처음 함께한 서울 여행", "광주 용봉동", "서울 남산", "11000", "서울", 37.5512, 126.9882, "2026-08-16", 3),
    sampleArchiveTrip("sample-suwon-1", "추억을 찾아 떠난 길", "대전광역시", "수원시", "41110", "수원시", 37.2636, 127.0286, "2026-08-02", 4),
    sampleArchiveTrip("sample-jeju", "우리의 첫 제주 여행", "제주시 연동", "서귀포시", "50130", "서귀포시", 33.2541, 126.5601, "2026-07-20", 4),
))

private fun sampleArchiveTrip(
    id: String, title: String, departure: String, destination: String,
    regionId: String, regionName: String, latitude: Double, longitude: Double, date: String, stories: Int,
) = ArchiveTripDto(id, title, departure, listOf(ArchiveDestinationDto(destination, latitude, longitude, regionId, regionName)), date, stories)
