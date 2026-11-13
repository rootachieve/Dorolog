package com.forever.dorolog

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class TripSelectionDataTest {
    private val gson = Gson()
    private val place = """{"name":"서울","address":"서울시","latitude":37.56,"longitude":126.98}"""

    @Test fun ongoingTrips_areSortedByLastDrive_andOpaqueIdsArePreserved() = runTest {
        val response = gson.fromJson("""{"trips":[
            {"id":"old/id","origin":$place,"lastDestination":null,"lastDrivenAt":"2026-11-01T00:00:00Z","storyCount":0},
            {"id":"new id","origin":$place,"lastDestination":$place,"lastDrivenAt":"2026-11-13T00:00:00Z","storyCount":4}
        ]}""", OngoingTripsResponseDto::class.java)
        val trips = TripSelectionRepository(loadTrips = { response }).load()
        assertEquals(listOf("new id", "old/id"), trips.map { it.id })
        assertNull(trips.last().lastDestination)
        assertEquals(4, trips.first().storyCount)
    }

    @Test fun malformedTrips_areRejectedInsteadOfInventingCoordinatesOrIds() {
        val valid = """{"trips":[{"id":"trip","origin":$place,"lastDestination":null,"lastDrivenAt":"2026-11-01T00:00:00Z","storyCount":0}]}"""
        for (bad in listOf(valid.replace("\"trip\"", "\" \""), valid.replace("37.56", "91"),
            valid.replace("126.98", "null"), valid.replace("2026-11-01T00:00:00Z", "yesterday"),
            valid.replace("\"storyCount\":0", "\"storyCount\":-1"), "{}")) {
            assertThrows(RuntimeException::class.java) {
                gson.fromJson(bad, OngoingTripsResponseDto::class.java).toModel()
            }
        }
    }

    @Test fun startRejectsDifferentServerTrip_andBlankCreateIds() {
        val repository = TripSelectionRepository(createTrip = { TripIdResponseDto(" ") },
            startTrip = { _, _ -> TripIdResponseDto("wrong") })
        assertThrows(IllegalArgumentException::class.java) { runTest { repository.create("request") } }
        val request = StartTripRequestDto("request", TripPlaceDto("서울", "서울시", 37.56, 126.98), null, "2026-11-14T00:00:00Z")
        assertThrows(IllegalArgumentException::class.java) { runTest { repository.start("chosen", request) } }
    }

    @Test fun retrofitEncodesOpaqueTripId_andSendsExplicitNullDestinationAndCoordinates() = runTest {
        var path = ""
        var body = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            path = request.url.encodedPath
            assertEquals("POST", request.method)
            body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"tripId":"chosen/server id"}""".toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://example.test/").client(client)
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().serializeNulls().create()))
            .build().create(TripSelectionApi::class.java)
        api.start("chosen/server id", StartTripRequestDto("request", TripPlaceDto("", "", 37.56, 126.98),
            null, "2026-11-14T00:00:00Z"))
        assertEquals("/trips/chosen%2Fserver%20id/start", path)
        val json = com.google.gson.JsonParser.parseString(body).asJsonObject
        assertTrue(json.get("destination").isJsonNull)
        assertEquals(37.56, json.getAsJsonObject("origin").get("latitude").asDouble, 0.0)
        assertEquals("", json.getAsJsonObject("origin").get("name").asString)
        assertEquals("2026-11-14T00:00:00Z", json.get("startedAt").asString)
    }
}
