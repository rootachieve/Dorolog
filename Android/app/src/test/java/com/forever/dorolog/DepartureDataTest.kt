package com.forever.dorolog

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class DepartureDataTest {
    @Test fun topicsSendsCurrentDestinationAndExplicitNoRoute() = runTest {
        val sent = mutableListOf<Request>()
        val sentBodies = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            sent += chain.request()
            sentBodies += Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("""{"topics":[{"id":"destination_1","question":"부산에서 무엇을 할까요?"},{"id":"destination_2","question":"함께 먹을 음식은?"},{"id":"family_travel_1","question":"기억에 남는 여행은?"},{"id":"family_travel_2","question":"기억에 남는 한 끼는?"}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://api.example/api/").client(client)
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().serializeNulls().create()))
            .build().create(DepartureApi::class.java)
        val repository = DepartureRepository(api::tripTopics)
        val topics = repository.load("trip-id", Destination("부산역", 35.1151, 129.0414, "부산광역시 동구 중앙대로 206"))
        repository.load("trip-id", Destination("주소 없는 장소"))
        repository.load("trip-id", null)
        assertTrue(sent.all { it.method == "POST" && it.url.encodedPath == "/api/trips/trip-id/topics" })
        val expected = listOf(
            """{"destination":{"name":"부산역","address":"부산광역시 동구 중앙대로 206","latitude":35.1151,"longitude":129.0414}}""",
            """{"destination":{"name":"주소 없는 장소","address":"","latitude":null,"longitude":null}}""",
            """{"destination":null}""",
        )
        assertEquals(expected.map(JsonParser::parseString), sentBodies.map(JsonParser::parseString))
        assertEquals(4, topics.size)
        assertEquals("부산에서 무엇을 할까요?", topics.first().question)
    }

    @Test fun invalidDestinationDoesNotSendTopics() = runTest {
        var sent = 0
        val repository = DepartureRepository { _, _ -> sent++; sampleDepartureResponse }
        for (destination in listOf(Destination(" "), Destination("부산", 35.0, null),
            Destination("부산", Double.NaN, 129.0), Destination("부산", 91.0, 129.0),
            Destination("부산", address = "a".repeat(1_001)))) {
            try {
                repository.load("trip-id", destination)
                fail("Invalid destination must fail before sending")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, sent)
    }
}
