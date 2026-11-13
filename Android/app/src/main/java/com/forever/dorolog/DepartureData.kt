package com.forever.dorolog

import com.google.gson.annotations.SerializedName
import com.google.gson.GsonBuilder
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url

data class RecommendedTopic(val id: String, val question: String)
data class Destination(
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null,
)
data class VehicleLocation(val address: String? = null, val latitude: Double? = null, val longitude: Double? = null)

data class RecommendationRequestDto(
    @SerializedName("destinationName") val destinationName: String?,
    @SerializedName("previousConversationIds") val previousConversationIds: List<String> = emptyList(),
    @SerializedName("tripId") val tripId: String? = null,
)

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
    @POST
    suspend fun recommendTopics(@Url url: String, @Body context: RecommendationRequestDto): DepartureResponseDto

    @POST("trips/{tripId}/topics")
    suspend fun tripTopics(@Path("tripId") tripId: String, @Body context: RecommendationRequestDto): DepartureResponseDto
}

class DepartureRepository(
    private val loadResponse: suspend (RecommendationRequestDto) -> DepartureResponseDto = { sampleDepartureResponse },
) {
    suspend fun load(context: RecommendationRequestDto): List<RecommendedTopic> = loadResponse(context).toModel()

    companion object {
        fun remote(baseUrl: String): DepartureRepository {
            val uri = java.net.URI(baseUrl)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && baseUrl.endsWith('/'))
            val api = Retrofit.Builder().baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create(GsonBuilder().serializeNulls().create()))
                .build().create(DepartureApi::class.java)
            return DepartureRepository { request ->
                api.tripTopics(requireNotNull(request.tripId).also { require(it.isNotBlank()) }, request)
            }
        }

        fun remote(baseUrl: String, endpoint: String): DepartureRepository {
            require(baseUrl.startsWith("https://"))
            val api = Retrofit.Builder().baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create()).build()
                .create(DepartureApi::class.java)
            return DepartureRepository { context -> api.recommendTopics(endpoint, context) }
        }
    }
}

// shortcut: API 계약 전에는 디자인 샘플을 공급하며, 서버 주소 확정 시 remote()로 교체한다.
internal val sampleDepartureResponse = DepartureResponseDto(
    topics = listOf(
        RecommendedTopicDto("together", "이번 여행에서 가족이랑 꼭 같이\n해보고 싶은게 있나요?"),
        RecommendedTopicDto("memories", "지난 여행에서 가장 기억에 남는 순간은 언제였나요?"),
        RecommendedTopicDto("arrival", "도착하면 가족과 가장 먼저 무엇을 해보고 싶나요?"),
    ),
)
