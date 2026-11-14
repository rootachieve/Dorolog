package com.forever.dorolog

import com.google.gson.annotations.SerializedName
import com.google.gson.GsonBuilder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

data class RecommendedTopic(val id: String, val question: String)
data class Destination(
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null,
)
data class VehicleLocation(val address: String? = null, val latitude: Double? = null, val longitude: Double? = null)

data class TopicDestinationDto(
    @SerializedName("name") val name: String,
    @SerializedName("address") val address: String,
    @SerializedName("latitude") val latitude: Double?,
    @SerializedName("longitude") val longitude: Double?,
)

data class TripTopicsRequestDto(@SerializedName("destination") val destination: TopicDestinationDto?)

data class RecommendedTopicDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("question") val question: String? = null,
)

data class DepartureResponseDto(
    @SerializedName("topics") val topics: List<RecommendedTopicDto>? = null,
) {
    fun toModel(): List<RecommendedTopic> {
        val items = requireNotNull(topics).map {
            RecommendedTopic(requireNotNull(it.id).also { id -> require(id.isNotBlank()) },
                requireNotNull(it.question).also { question -> require(question.isNotBlank()) })
        }
        require(items.map { it.id }.distinct().size == items.size)
        return items
    }
}

interface DepartureApi {
    @POST("trips/{tripId}/topics")
    suspend fun tripTopics(@Path("tripId") tripId: String, @Body request: TripTopicsRequestDto): DepartureResponseDto
}

class DepartureRepository(
    private val loadResponse: suspend (String, TripTopicsRequestDto) -> DepartureResponseDto = { _, _ -> sampleDepartureResponse },
) {
    suspend fun load(tripId: String, destination: Destination?): List<RecommendedTopic> {
        require(tripId.isNotBlank())
        val point = destination?.let {
            require(it.name.isNotBlank())
            require((it.latitude == null) == (it.longitude == null))
            it.latitude?.let { latitude -> require(latitude.isFinite() && latitude in -90.0..90.0) }
            it.longitude?.let { longitude -> require(longitude.isFinite() && longitude in -180.0..180.0) }
            val address = it.address.orEmpty().also { address -> require(address.length <= 1_000) }
            TopicDestinationDto(it.name, address, it.latitude, it.longitude)
        }
        return loadResponse(tripId, TripTopicsRequestDto(point)).toModel()
    }

    companion object {
        fun remote(baseUrl: String): DepartureRepository {
            val uri = java.net.URI(baseUrl)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && baseUrl.endsWith('/'))
            val api = Retrofit.Builder().baseUrl(baseUrl)
                .client(OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build())
                .addConverterFactory(GsonConverterFactory.create(GsonBuilder().serializeNulls().create()))
                .build().create(DepartureApi::class.java)
            return DepartureRepository(api::tripTopics)
        }
    }
}

// 디자인 미리보기와 테스트에서 사용하는 추천 질문 샘플.
internal val sampleDepartureResponse = DepartureResponseDto(
    topics = listOf(
        RecommendedTopicDto("together", "이번 여행에서 가족이랑 꼭 같이\n해보고 싶은게 있나요?"),
        RecommendedTopicDto("memories", "지난 여행에서 가장 기억에 남는 순간은 언제였나요?"),
        RecommendedTopicDto("arrival", "도착하면 가족과 가장 먼저 무엇을 해보고 싶나요?"),
    ),
)
