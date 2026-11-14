package com.forever.dorolog

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class TripSelectionDataTest {
    private val gson = Gson()
    private val trip = """{"id":"chosen/server id","originName":"서울","originLatitude":37.56,"originLongitude":126.98,
        "destinationName":null,"destinationLatitude":null,"destinationLongitude":null,
        "status":"active","startedAt":"2026-11-13T00:00:00Z","endedAt":null,"storyCount":0}"""

    @Test fun recentTrips_acceptFlatFinishedTripsWithoutCoordinates_andKeepServerOrder() = runTest {
        val response = gson.fromJson("""{"trips":[
            {"id":"recently-resumed","originName":"서울","originLatitude":null,"originLongitude":null,
             "destinationName":"부산","destinationAddress":"부산광역시 동구","destinationLatitude":null,"destinationLongitude":null,
             "status":"finished","startedAt":"2026-11-01T00:00:00Z","endedAt":"2026-11-02T00:00:00Z","storyCount":4},
            $trip
        ]}""", OngoingTripsResponseDto::class.java)
        val trips = TripSelectionRepository(loadTrips = { response }).load()
        assertEquals(listOf("recently-resumed", "chosen/server id"), trips.map { it.id })
        assertEquals("서울", trips.first().origin.name)
        assertNull(trips.first().origin.latitude)
        assertEquals("부산", trips.first().lastDestination?.name)
        assertEquals("부산광역시 동구", trips.first().lastDestination?.address)
        assertEquals(4, trips.first().storyCount)
        assertNull(trips.last().lastDestination)
    }

    @Test fun missingDestinationAddressDoesNotInventAnAddressFromThePlaceName() {
        val response = gson.fromJson(trip.replace("\"destinationName\":null", "\"destinationName\":\"부산역\""),
            OngoingTripDto::class.java)
        assertEquals("부산역", response.toModel().lastDestination?.name)
        assertEquals("", response.toModel().lastDestination?.address)
    }

    @Test fun malformedTrips_areRejectedInsteadOfInventingCoordinatesOrIds() {
        for (bad in listOf(trip.replace("chosen/server id", " "), trip.replace("37.56", "91"),
            trip.replace("126.98", "null"), trip.replace("서울", " "), trip.replace("active", "draft"),
            trip.replace("2026-11-13T00:00:00Z", "yesterday"), trip.replace("\"storyCount\":0", "\"storyCount\":-1"), "{}")) {
            assertThrows(RuntimeException::class.java) { gson.fromJson(bad, OngoingTripDto::class.java).toModel() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            gson.fromJson("""{"trips":[$trip,$trip]}""", OngoingTripsResponseDto::class.java).toModel()
        }
    }

    @Test fun resumeRejectsDifferentServerTrip_andCreateUsesFullResponseId() {
        val response = gson.fromJson(trip, OngoingTripDto::class.java)
        val repository = TripSelectionRepository(createTrip = { response }, resumeTrip = { response })
        runTest { assertEquals("chosen/server id", repository.create(CreateTripRequestDto("서울")).id) }
        assertThrows(IllegalArgumentException::class.java) { runTest { repository.resume("different") } }
    }

    @Test fun createRejectsBlankNamesAndInvalidCoordinatePairs() {
        assertThrows(IllegalArgumentException::class.java) { CreateTripRequestDto(" ") }
        assertThrows(IllegalArgumentException::class.java) { CreateTripRequestDto("서울", 37.56, null) }
        assertThrows(IllegalArgumentException::class.java) { CreateTripRequestDto("서울", Double.NaN, 126.98) }
        assertThrows(IllegalArgumentException::class.java) { CreateTripRequestDto("서울", destinationName = " ") }
        assertThrows(IllegalArgumentException::class.java) {
            CreateTripRequestDto("서울", destinationName = "부산", destinationLatitude = 35.0)
        }
    }

    @Test fun retrofitUsesRecentLimitFlatCreateAndBodylessResume_withOpaqueId() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val bodies = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            bodies += request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (request.method == "GET") """{"trips":[$trip]}""" else trip).toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://example.test/api/").client(client)
            .addConverterFactory(GsonConverterFactory.create(gson)).build().create(TripSelectionApi::class.java)
        api.ongoingTrips()
        api.create(CreateTripRequestDto("서울", 37.56, 126.98))
        api.create(CreateTripRequestDto("서울", 37.56, 126.98, "부산", 35.16, 129.16,
            destinationAddress = "부산광역시 동구"))
        api.resume("chosen/server id")
        assertEquals("/api/trips?limit=6", requests[0].url.encodedPath + "?" + requests[0].url.encodedQuery)
        assertEquals("/api/trips", requests[1].url.encodedPath)
        val withoutDestination = JsonParser.parseString(bodies[1]).asJsonObject
        assertEquals(setOf("originName", "originLatitude", "originLongitude"), withoutDestination.keySet())
        assertEquals("서울", withoutDestination["originName"].asString)
        assertEquals(37.56, withoutDestination["originLatitude"].asDouble, 0.0)
        val withDestination = JsonParser.parseString(bodies[2]).asJsonObject
        assertEquals("부산", withDestination["destinationName"].asString)
        assertEquals("부산광역시 동구", withDestination["destinationAddress"].asString)
        assertEquals(129.16, withDestination["destinationLongitude"].asDouble, 0.0)
        assertFalse(withDestination.has("requestId"))
        assertEquals("POST", requests[3].method)
        assertEquals("/api/trips/chosen%2Fserver%20id/resume", requests[3].url.encodedPath)
        assertEquals("", bodies[3])
    }
}
