package com.forever.dorolog

import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.net.URI
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

data class ParkedStory(val id: String, val title: String, val region: String)

data class TripStories(val stories: List<ParkedStory>, val savedStoryIds: Set<String>) {
    internal fun validate() {
        require(stories.all { it.id.isNotBlank() && it.title.isNotBlank() && it.region.isNotBlank() })
        val ids = stories.map { it.id }.toSet()
        require(ids.size == stories.size && savedStoryIds.all { it in ids })
    }
}

data class ParkedStoryDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("region") val region: String? = null,
)

data class ParkedStoriesResponseDto(
    @SerializedName("stories") val stories: List<ParkedStoryDto>? = null,
    @SerializedName("savedStoryIds") val savedStoryIds: List<String>? = null,
) {
    fun toModel(): TripStories {
        val saved = requireNotNull(savedStoryIds)
        require(saved.distinct().size == saved.size)
        return TripStories(requireNotNull(stories).map {
            ParkedStory(requireNotNull(it.id), requireNotNull(it.title), requireNotNull(it.region))
        }, saved.toSet()).also { it.validate() }
    }
}

data class SaveParkedStoriesRequestDto(@SerializedName("storyIds") val storyIds: List<String>)

interface TripApi {
    @GET("trips/{tripId}/stories")
    suspend fun stories(@Path("tripId") tripId: String): ParkedStoriesResponseDto

    @PUT("trips/{tripId}/saved-stories")
    suspend fun saveStories(
        @Path("tripId") tripId: String,
        @Body request: SaveParkedStoriesRequestDto,
    )

    @POST("trips/{tripId}/finish")
    suspend fun finishTrip(@Path("tripId") tripId: String)
}

class TripRepository(
    private val loadStories: suspend (String) -> TripStories = {
        throw IOException("이야기 서버 주소가 설정되지 않았어요")
    },
    private val saveStories: suspend (String, Set<String>) -> Unit = { _, _ ->
        throw IOException("이야기 서버 주소가 설정되지 않았어요")
    },
    private val finishTrip: suspend (String) -> Unit = {
        throw IOException("여행 서버 주소가 설정되지 않았어요")
    },
) {
    suspend fun load(tripId: String): TripStories {
        require(tripId.isNotBlank())
        return loadStories(tripId).also { it.validate() }
    }

    suspend fun save(tripId: String, storyIds: Set<String>) {
        require(tripId.isNotBlank() && storyIds.all { it.isNotBlank() })
        saveStories(tripId, storyIds.toSet())
    }

    suspend fun finish(tripId: String) {
        require(tripId.isNotBlank())
        finishTrip(tripId)
    }

    companion object {
        fun remote(baseUrl: String): TripRepository {
            val uri = URI(baseUrl)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && baseUrl.endsWith('/'))
            val api = Retrofit.Builder().baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create()).build().create(TripApi::class.java)
            return TripRepository(
                loadStories = { trip -> api.stories(trip).toModel() },
                saveStories = { trip, ids ->
                    api.saveStories(trip, SaveParkedStoriesRequestDto(ids.sorted()))
                },
                finishTrip = api::finishTrip,
            )
        }
    }
}
