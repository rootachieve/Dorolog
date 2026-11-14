package com.forever.dorolog

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.net.URI
import java.time.Instant
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

data class TripPlace(val name: String, val address: String, val latitude: Double, val longitude: Double)

data class OngoingTrip(
    val id: String,
    val origin: TripPlace,
    val lastDestination: TripPlace?,
    val lastDrivenAt: Instant,
    val storyCount: Int,
)

data class TripPlaceDto(
    @SerializedName("name") val name: String? = null,
    @SerializedName("address") val address: String? = null,
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null,
) {
    fun toModel() = TripPlace(requireNotNull(name).trim(), requireNotNull(address).trim(),
        requireNotNull(latitude).also { require(it.isFinite() && it in -90.0..90.0) },
        requireNotNull(longitude).also { require(it.isFinite() && it in -180.0..180.0) })
}

data class OngoingTripDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("origin") val origin: TripPlaceDto? = null,
    @SerializedName("lastDestination") val lastDestination: TripPlaceDto? = null,
    @SerializedName("lastDrivenAt") val lastDrivenAt: String? = null,
    @SerializedName("storyCount") val storyCount: Int? = null,
)

data class OngoingTripsResponseDto(@SerializedName("trips") val trips: List<OngoingTripDto?>? = null) {
    fun toModel(): List<OngoingTrip> {
        val items = requireNotNull(trips).map { item ->
            val trip = requireNotNull(item)
            OngoingTrip(requireNotNull(trip.id).also { require(it.isNotBlank()) },
                requireNotNull(trip.origin).toModel(), trip.lastDestination?.toModel(),
                Instant.parse(requireNotNull(trip.lastDrivenAt)),
                requireNotNull(trip.storyCount).also { require(it >= 0) })
        }
        require(items.map { it.id }.distinct().size == items.size)
        return items.sortedByDescending { it.lastDrivenAt }
    }
}

data class CreateTripRequestDto(@SerializedName("requestId") val requestId: String)
data class TripIdResponseDto(@SerializedName("tripId") val tripId: String? = null) {
    fun validatedId() = requireNotNull(tripId).also { require(it.isNotBlank()) }
}
data class StartTripRequestDto(
    @SerializedName("requestId") val requestId: String,
    @SerializedName("origin") val origin: TripPlaceDto,
    @SerializedName("destination") val destination: TripPlaceDto?,
    @SerializedName("startedAt") val startedAt: String,
)

interface TripSelectionApi {
    @GET("trips/ongoing") suspend fun ongoingTrips(): OngoingTripsResponseDto
    @POST("trips") suspend fun create(@Body request: CreateTripRequestDto): TripIdResponseDto
    @POST("trips/{tripId}/start") suspend fun start(
        @Path("tripId") tripId: String,
        @Body request: StartTripRequestDto,
    ): TripIdResponseDto
}

class TripSelectionRepository(
    private val loadTrips: suspend () -> OngoingTripsResponseDto = { throw IOException("여행 서버 주소가 설정되지 않았어요") },
    private val createTrip: suspend (CreateTripRequestDto) -> TripIdResponseDto = { throw IOException("여행 서버 주소가 설정되지 않았어요") },
    private val startTrip: suspend (String, StartTripRequestDto) -> TripIdResponseDto = { _, _ -> throw IOException("여행 서버 주소가 설정되지 않았어요") },
) {
    suspend fun load() = loadTrips().toModel()
    suspend fun create(requestId: String): String {
        require(requestId.isNotBlank())
        return createTrip(CreateTripRequestDto(requestId)).validatedId()
    }
    suspend fun start(tripId: String, request: StartTripRequestDto): String {
        require(tripId.isNotBlank() && request.requestId.isNotBlank())
        request.origin.toModel()
        request.destination?.toModel()
        Instant.parse(request.startedAt)
        return startTrip(tripId, request).validatedId().also { require(it == tripId) }
    }
    companion object {
        fun remote(baseUrl: String): TripSelectionRepository {
            val uri = URI(baseUrl)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && baseUrl.endsWith('/'))
            val api = Retrofit.Builder().baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create(GsonBuilder().serializeNulls().create()))
                .build().create(TripSelectionApi::class.java)
            return TripSelectionRepository(api::ongoingTrips, api::create, api::start)
        }
    }
}
