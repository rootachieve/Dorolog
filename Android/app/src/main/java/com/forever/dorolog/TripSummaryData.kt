package com.forever.dorolog

import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.net.URI
import java.time.DateTimeException
import java.time.Instant
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

data class SavedTripStory(
    val key: String,
    val id: String,
    val title: String,
    val region: String,
    val occurredAt: Instant?,
    val content: String?,
    val conversationExcerpts: List<String>,
)

data class TripSummaryStoryDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("region") val region: String? = null,
    @SerializedName("recordedAt") val recordedAt: String? = null,
    @SerializedName("summary") val summary: String? = null,
    @SerializedName("highlights") val highlights: List<String?>? = null,
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null,
)

data class TripSummaryResponseDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("stories") val stories: List<TripSummaryStoryDto?>? = null,
    @SerializedName("savedStoryIds") val savedStoryIds: List<String?>? = null,
) {
    fun toModel(expectedTripId: String): List<SavedTripStory> {
        require(id == summaryId(expectedTripId))
        val models = requireNotNull(stories).map { nullableStory ->
            val story = requireNotNull(nullableStory)
            val id = summaryId(story.id)
            require((story.latitude == null) == (story.longitude == null))
            story.latitude?.let { require(it.isFinite() && it in -90.0..90.0) }
            story.longitude?.let { require(it.isFinite() && it in -180.0..180.0) }
            SavedTripStory(
                key = id, id = id,
                title = requireNotNull(story.title).also { require(it.isNotBlank()) },
                region = requireNotNull(story.region).also { require(it.isNotBlank()) },
                occurredAt = story.recordedAt?.let(::summaryTime),
                content = story.summary?.takeIf { it.isNotBlank() },
                conversationExcerpts = story.highlights.orEmpty().map { requireNotNull(it) }
                    .filter { it.isNotBlank() },
            )
        }
        val storyIds = models.map { it.id }.toSet()
        require(storyIds.size == models.size)
        val savedIds = requireNotNull(savedStoryIds).map(::summaryId)
        require(savedIds.distinct().size == savedIds.size && storyIds.containsAll(savedIds))
        val saved = savedIds.toSet()
        return models.filter { it.id in saved }
            .sortedWith(compareBy<SavedTripStory> { it.occurredAt == null }.thenBy { it.occurredAt })
    }
}

interface TripSummaryApi {
    @GET("trips/{tripId}")
    suspend fun trip(@Path("tripId") tripId: String): TripSummaryResponseDto
}

class TripSummaryRepository(
    private val loadResponse: suspend (String) -> TripSummaryResponseDto = {
        throw IOException("이야기 서버 주소가 설정되지 않았어요")
    },
) {
    suspend fun load(tripId: String): List<SavedTripStory> = loadResponse(summaryId(tripId)).toModel(tripId)

    companion object {
        fun remote(baseUrl: String): TripSummaryRepository {
            val uri = URI(baseUrl)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && baseUrl.endsWith('/'))
            val api = Retrofit.Builder().baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create()).build().create(TripSummaryApi::class.java)
            return TripSummaryRepository(api::trip)
        }
    }
}

private fun summaryId(value: String?): String = requireNotNull(value).also { require(it.isNotBlank()) }

private fun summaryTime(value: String): Instant = try {
    Instant.parse(value)
} catch (error: DateTimeException) {
    throw IllegalArgumentException("이야기 시간 형식을 확인할 수 없어요", error)
}
