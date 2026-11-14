package com.forever.dorolog

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import retrofit2.http.Query

class ArchiveDataContractTest {
    @Test fun tripListRequest_usesSupportedLimitQuery() {
        val method = ArchiveApi::class.java.methods.single { it.name == "trips" }
        val query = method.parameterAnnotations.flatten().filterIsInstance<Query>().single()
        assertEquals("limit", query.value)
    }

    @Test fun flatServerTrip_withNoDestination_remainsUsable() {
        val response = Gson().fromJson("""{"trips":[{
          "id":"trip-without-destination", "originName":"서울",
          "originLatitude":null, "originLongitude":null,
          "destinationName":null, "destinationLatitude":null, "destinationLongitude":null,
          "status":"finished", "startedAt":"2026-11-14T05:30:00+00:00",
          "endedAt":"2026-11-14T08:10:00+00:00", "storyCount":2
        }]}""", ArchiveResponseDto::class.java)
        val trip = response.toModel().single()
        assertEquals("trip-without-destination", trip.id)
        assertEquals("서울", trip.departureName)
        assertNull(trip.destinationName)
        assertNull(trip.destinationLatitude)
        assertNull(trip.destinationLongitude)
        assertEquals("2026-11-14", trip.date.toString())
        assertEquals(2, trip.storyCount)
    }

    @Test fun savedStoryMap_usesStoryRegionsAndGlobalTotals_withUnknownCoordinates() {
        val map = mapResponse()
        val content = map.toModel(emptyList())
        assertEquals(2, content.storyCount)
        assertEquals(2, content.tripCount)
        assertEquals("천안", content.regions.first().name)
        assertEquals(1, content.regions.first().storyCount)
        assertEquals("outside-latest-fifty", content.stories.first().tripId)
        assertNull(content.regions.last().latitude)
        assertNull(content.regions.last().longitude)
        assertNull(content.stories.last().latitude)
        assertTrue(content.trips.isEmpty())
    }

    @Test fun savedStories_withoutRecordedTime_areRetained() {
        val map = mapResponse()
        val stories = requireNotNull(map.stories).map { requireNotNull(it).copy(recordedAt = null) }
        val content = map.copy(stories = stories).toModel(emptyList())
        assertEquals(2, content.stories.size)
        assertNull(content.stories.first().recordedAt)
        assertNull(content.stories.first().date)
    }

    @Test fun sameStoryIdInDifferentTrips_isValid_butDuplicateWithinTripIsRejected() {
        val map = mapResponse()
        val stories = requireNotNull(map.stories).map { requireNotNull(it).copy(id = "shared-id") }
        assertEquals(2, map.copy(stories = stories).toModel(emptyList()).stories.size)
        assertThrows(Exception::class.java) {
            map.copy(stories = listOf(stories.first(), stories.first())).toModel(emptyList())
        }
    }

    @Test fun malformedCoordinatesAndCounts_areRejectedBeforeRendering() {
        val map = mapResponse()
        val story = requireNotNull(map.stories?.first())
        val invalid = listOf(
            story.copy(latitude = null, longitude = 127.1),
            story.copy(latitude = Double.NaN),
            story.copy(latitude = 90.1),
            story.copy(longitude = -180.1),
            story.copy(tripId = " "),
            story.copy(recordedAt = "invalid"),
        )
        invalid.forEach { item ->
            assertThrows(Exception::class.java) {
                map.copy(stories = listOf(item, requireNotNull(map.stories?.last()))).toModel(emptyList())
            }
        }
        assertThrows(Exception::class.java) { map.copy(storyCount = 3).toModel(emptyList()) }
        assertThrows(Exception::class.java) { map.copy(tripCount = 1).toModel(emptyList()) }
        assertThrows(Exception::class.java) {
            map.copy(regions = listOf(ArchiveRegionDto("천안", 2, 36.8, 127.1))).toModel(emptyList())
        }
    }

    @Test fun invalidTripFields_areRejected_butOpaqueIdsArePreserved() {
        val trip = ArchiveTripDto("여행: 2026/가족 + " + "a".repeat(160), "서울", null, null,
            null, null, null, "active", "2026-11-14T05:30:00Z", null, 0)
        assertEquals(trip.id, ArchiveResponseDto(listOf(trip)).toModel().single().id)
        listOf(trip.copy(id = " "), trip.copy(originName = " "), trip.copy(originLatitude = 37.5),
            trip.copy(destinationLatitude = 0.0), trip.copy(storyCount = -1), trip.copy(storyCount = null),
            trip.copy(startedAt = "2026-02-30"), trip.copy(status = "unknown")).forEach { invalid ->
            assertThrows(Exception::class.java) { ArchiveResponseDto(listOf(invalid)).toModel() }
        }
        assertThrows(Exception::class.java) { ArchiveResponseDto(listOf(trip, trip)).toModel() }
        assertThrows(Exception::class.java) { Gson().fromJson("{}", ArchiveResponseDto::class.java).toModel() }
    }

    @Test fun remoteUrl_requiresHttpsHostTrailingSlash_andRejectsCredentialsQueryOrFragment() {
        listOf("http://example.com/", "https:///", "https://user:pass@example.com/", "https://example.com/api",
            "https://example.com/?sort=latest", "https://example.com/#archive").forEach { url ->
            assertThrows(Exception::class.java) { ArchiveRepository.remote(url) }
        }
        assertNotNull(ArchiveRepository.remote("https://example.com/api/"))
    }

    private fun mapResponse() = Gson().fromJson("""{
      "storyCount":2, "tripCount":2,
      "regions":[
        {"region":"천안", "storyCount":1, "latitude":36.8, "longitude":127.1},
        {"region":"위치 미상", "storyCount":1, "latitude":null, "longitude":null}
      ],
      "stories":[
        {"id":"story-one", "tripId":"outside-latest-fifty", "title":"천안에서 나눈 이야기", "region":"천안",
          "latitude":36.8, "longitude":127.1, "summary":"천안에서 나눈 대화", "highlights":["즐거웠어"],
          "recordedAt":"2026-11-14T14:30:00+09:00"},
        {"id":"story-two", "tripId":"another-trip", "title":"가족의 추억", "region":"위치 미상",
          "latitude":null, "longitude":null, "summary":"가족과 나눈 대화", "highlights":[],
          "recordedAt":"2026-11-13T14:30:00+09:00"}
      ]
    }""", ArchiveMapResponseDto::class.java)

}
