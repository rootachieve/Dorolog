package com.forever.dorolog

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.net.URI
import java.time.Instant
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

data class TripPlace(val name: String, val address: String, val latitude: Double?, val longitude: Double?)

data class OngoingTrip(
    val id: String,
    val origin: TripPlace,
    val lastDestination: TripPlace?,
    val lastDrivenAt: Instant,
    val storyCount: Int,
    val status: String,
)

private fun validateTripCoordinates(latitude: Double?, longitude: Double?) {
    require((latitude == null) == (longitude == null))
    latitude?.let { require(it.isFinite() && it in -90.0..90.0) }
    longitude?.let { require(it.isFinite() && it in -180.0..180.0) }
}

data class OngoingTripDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("originName") val originName: String? = null,
    @SerializedName("originLatitude") val originLatitude: Double? = null,
    @SerializedName("originLongitude") val originLongitude: Double? = null,
    @SerializedName("destinationName") val destinationName: String? = null,
    @SerializedName("destinationAddress") val destinationAddress: String? = null,
    @SerializedName("destinationLatitude") val destinationLatitude: Double? = null,
    @SerializedName("destinationLongitude") val destinationLongitude: Double? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("startedAt") val startedAt: String? = null,
    @SerializedName("endedAt") val endedAt: String? = null,
    @SerializedName("storyCount") val storyCount: Int? = null,
) {
    fun toModel(): OngoingTrip {
        val tripId = requireNotNull(id).also { require(it.isNotBlank()) }
        val origin = requireNotNull(originName).trim().also { require(it.isNotBlank()) }
        validateTripCoordinates(originLatitude, originLongitude)
        validateTripCoordinates(destinationLatitude, destinationLongitude)
        val tripStatus = requireNotNull(status)
        require(tripStatus == "active" || tripStatus == "finished")
        val started = Instant.parse(requireNotNull(startedAt))
        val ended = endedAt?.let(Instant::parse)
        val destination = destinationName?.trim()?.also { require(it.isNotBlank()) }
        require(destination != null || destinationLatitude == null)
        return OngoingTrip(tripId, TripPlace(origin, origin, originLatitude, originLongitude),
            destination?.let { TripPlace(it, destinationAddress.orEmpty(), destinationLatitude, destinationLongitude) },
            ended ?: started, requireNotNull(storyCount).also { require(it >= 0) }, tripStatus)
    }
}

data class OngoingTripsResponseDto(@SerializedName("trips") val trips: List<OngoingTripDto?>? = null) {
    fun toModel(): List<OngoingTrip> {
        val items = requireNotNull(trips).map { requireNotNull(it).toModel() }
        require(items.map { it.id }.distinct().size == items.size)
        return items
    }
}

data class CreateTripRequestDto(
    @SerializedName("originName") val originName: String,
    @SerializedName("originLatitude") val originLatitude: Double? = null,
    @SerializedName("originLongitude") val originLongitude: Double? = null,
    @SerializedName("destinationName") val destinationName: String? = null,
    @SerializedName("destinationLatitude") val destinationLatitude: Double? = null,
    @SerializedName("destinationLongitude") val destinationLongitude: Double? = null,
    @SerializedName("destinationAddress") val destinationAddress: String? = null,
) {
    init {
        require(originName.isNotBlank())
        require(destinationName == null || destinationName.isNotBlank())
        validateTripCoordinates(originLatitude, originLongitude)
        validateTripCoordinates(destinationLatitude, destinationLongitude)
        require(destinationName != null || destinationLatitude == null)
    }
}

interface TripSelectionApi {
    @GET("trips?limit=6") suspend fun ongoingTrips(): OngoingTripsResponseDto
    @POST("trips") suspend fun create(@Body request: CreateTripRequestDto): OngoingTripDto
    @POST("trips/{tripId}/resume") suspend fun resume(@Path("tripId") tripId: String): OngoingTripDto
}

class TripSelectionRepository(
    private val loadTrips: suspend () -> OngoingTripsResponseDto = { throw IOException("여행 서버 주소가 설정되지 않았어요") },
    private val createTrip: suspend (CreateTripRequestDto) -> OngoingTripDto = { throw IOException("여행 서버 주소가 설정되지 않았어요") },
    private val resumeTrip: suspend (String) -> OngoingTripDto = { throw IOException("여행 서버 주소가 설정되지 않았어요") },
) {
    suspend fun load() = loadTrips().toModel()
    suspend fun create(request: CreateTripRequestDto) = createTrip(request).toModel()
    suspend fun resume(tripId: String): OngoingTrip {
        require(tripId.isNotBlank())
        return resumeTrip(tripId).toModel().also { require(it.id == tripId) }
    }
    companion object {
        fun remote(baseUrl: String): TripSelectionRepository {
            val uri = URI(baseUrl)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && baseUrl.endsWith('/'))
            val api = Retrofit.Builder().baseUrl(baseUrl)
                .client(OkHttpClient.Builder().retryOnConnectionFailure(false).build())
                .addConverterFactory(GsonConverterFactory.create(Gson()))
                .build().create(TripSelectionApi::class.java)
            return TripSelectionRepository(api::ongoingTrips, api::create, api::resume)
        }
    }
}
