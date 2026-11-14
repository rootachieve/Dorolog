package com.forever.dorolog

import com.google.gson.Gson
import java.io.IOException
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
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class TripDataTest {
    @Test fun newestFirstStoryOrderIsPreservedFromServerResponse() {
        val response = Gson().fromJson(
            """{"stories":[{"id":"z-latest","title":"최근 이야기","region":"부산"},{"id":"a-older","title":"이전 이야기","region":"서울"}],"savedStoryIds":["a-older"]}""",
            ParkedStoriesResponseDto::class.java,
        )
        assertEquals(listOf("z-latest", "a-older"), response.toModel().stories.map { it.id })
    }

    @Test fun storyResponseRequiresUniqueIdsAndRealContent() {
        val gson = Gson()
        val valid = gson.fromJson(
            """{"stories":[{"id":"one","title":"바다 이야기","region":"해운대구"}],"savedStoryIds":["one"]}""",
            ParkedStoriesResponseDto::class.java,
        )
        assertEquals(TripStories(listOf(ParkedStory("one", "바다 이야기", "해운대구")), setOf("one")), valid.toModel())
        for (json in listOf(
            "{}", """{"stories":[]}""",
            """{"stories":[{"id":"one","title":"","region":"서울"}],"savedStoryIds":[]}""",
            """{"stories":[{"id":"one","title":"이야기"}],"savedStoryIds":[]}""",
            """{"stories":[{"id":"one","title":"이야기","region":"서울"},{"id":"one","title":"중복","region":"부산"}],"savedStoryIds":[]}""",
            """{"stories":[],"savedStoryIds":["unknown"]}""",
            """{"stories":[{"id":"one","title":"이야기","region":"서울"}],"savedStoryIds":["one","one"]}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                gson.fromJson(json, ParkedStoriesResponseDto::class.java).toModel()
            }
        }
    }

    @Test fun choosingAnExistingTripLoadsItsServerSelection() = runTest {
        val expected = TripStories(listOf(ParkedStory("old-story", "이전 이야기", "서울")), setOf("old-story"))
        val repository = TripRepository(loadStories = { trip -> assertEquals("chosen-trip", trip); expected })
        assertEquals(expected, repository.load("chosen-trip"))
    }

    @Test fun selectionSaveSendsTheWholeSet_includingDeselectionAndEmptySelection() = runTest {
        val sent = mutableListOf<Set<String>>()
        val repository = TripRepository(saveStories = { trip, ids ->
            assertEquals("trip", trip); sent += ids
        })
        repository.save("trip", setOf("one", "two"))
        repository.save("trip", setOf("two", "three"))
        repository.save("trip", emptySet())
        assertEquals(listOf(setOf("one", "two"), setOf("two", "three"), emptySet<String>()), sent)
    }

    @Test fun retrofitContractScopesStoriesToTrip_savesIds_andFinishesWithBodylessPost() = runTest {
        val requests = mutableListOf<Pair<Request, String>>()
        var finishStatus = 204
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            requests += request to body
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("OK")
                .code(if (request.method == "GET") 200 else if (request.method == "POST") finishStatus else 204)
                .body((if (request.method == "GET") """{"stories":[],"savedStoryIds":[]}""" else "")
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://api.example/v1/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(TripApi::class.java)
        assertEquals(TripStories(emptyList(), emptySet()), api.stories("trip").toModel())
        api.saveStories("trip", SaveParkedStoriesRequestDto(listOf("one", "three")))
        api.saveStories("trip", SaveParkedStoriesRequestDto(emptyList()))
        api.finishTrip("trip")
        assertEquals("/v1/trips/trip/stories", requests[0].first.url.encodedPath)
        assertEquals("PUT", requests[1].first.method)
        assertEquals("/v1/trips/trip/saved-stories", requests[1].first.url.encodedPath)
        assertEquals("""{"storyIds":["one","three"]}""", requests[1].second)
        assertEquals("""{"storyIds":[]}""", requests[2].second)
        assertEquals("POST", requests[3].first.method)
        assertEquals("/v1/trips/trip/finish", requests[3].first.url.encodedPath)
        assertEquals("", requests[3].second)
        finishStatus = 503
        try {
            api.finishTrip("trip")
            fail("Unsuccessful finish must fail")
        } catch (error: HttpException) { assertEquals(503, error.code()) }
    }

    @Test fun finishRequiresTripId_andPropagatesBackendFailures() = runTest {
        val requested = mutableListOf<String>()
        val failure = IOException("offline")
        val repository = TripRepository(finishTrip = { trip -> requested += trip; throw failure })
        try {
            repository.finish(" ")
            fail("Blank trip must fail")
        } catch (_: IllegalArgumentException) { }
        assertTrue(requested.isEmpty())
        try {
            repository.finish("chosen-trip")
            fail("Backend failure must propagate")
        } catch (error: IOException) { assertSame(failure, error) }
        assertEquals(listOf("chosen-trip"), requested)
        try {
            TripRepository().finish("chosen-trip")
            fail("Missing backend must fail")
        } catch (_: IOException) { }
    }

    @Test fun unconfiguredRepositoryCannotPretendToSave_andInsecureUrlsAreRejected() = runTest {
        assertThrows(IllegalArgumentException::class.java) { TripRepository.remote("http://api.example/") }
        assertThrows(IllegalArgumentException::class.java) { TripRepository.remote("https://user:pass@api.example/") }
        assertThrows(IllegalArgumentException::class.java) { TripRepository.remote("https://api.example/?token=x") }
        assertThrows(IllegalArgumentException::class.java) { TripRepository.remote("https://api.example") }
        try {
            TripRepository().save("trip", setOf("story"))
            fail("Missing backend must fail")
        } catch (_: IOException) { }
    }
}
