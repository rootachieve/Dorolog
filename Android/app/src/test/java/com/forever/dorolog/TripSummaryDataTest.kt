package com.forever.dorolog

import com.google.gson.Gson
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TripSummaryDataTest {
    @Test fun tripDetailContract_mapsSavedStorySummaryHighlightsAndRecordedAt() = runTest {
        val response = decode("""{
          "id":"trip-id",
          "originName":"서울","originLatitude":37.5665,"originLongitude":126.978,
          "destinationName":"부산","destinationLatitude":35.1796,"destinationLongitude":129.0756,
          "status":"finished","startedAt":"2026-11-14T05:30:00+00:00",
          "endedAt":"2026-11-14T08:10:00+00:00","storyCount":1,
          "stories":[
            {"id":"story-id","title":"광안리에서 타고 싶은 자전거","region":"천안",
             "latitude":36.8151,"longitude":127.1139,
             "summary":"가족이 광안리에서 함께 자전거를 타고 싶다고 이야기했다.",
             "highlights":["다 같이 자전거 타고 싶어"],"recordedAt":"2026-11-14T14:30:00+09:00"},
            {"id":"excluded","title":"저장 해제한 이야기","region":"천안",
             "latitude":null,"longitude":null,"summary":"제외","highlights":[],"recordedAt":null}
          ],"savedStoryIds":["story-id"]
        }""")

        val story = TripSummaryRepository { response }.load("trip-id").single()

        assertEquals("story-id", story.id)
        assertEquals("광안리에서 타고 싶은 자전거", story.title)
        assertEquals("천안", story.region)
        assertEquals("가족이 광안리에서 함께 자전거를 타고 싶다고 이야기했다.", story.content)
        assertEquals(listOf("다 같이 자전거 타고 싶어"), story.conversationExcerpts)
        assertEquals(Instant.parse("2026-11-14T05:30:00Z"), story.occurredAt)
    }

    @Test fun storyCoordinates_rejectUnpairedOutOfRangeAndNonfiniteValues() {
        val valid = """{"id":"trip-1","tripId":"trip-1","savedStoryIds":["s"],"stories":[
          {"id":"s","title":"제목","region":"천안","latitude":36.8151,"longitude":127.1139,
           "summary":"내용","highlights":[],"recordedAt":null}
        ]}"""
        for (json in listOf(
            valid.replace("36.8151", "null"),
            valid.replace("127.1139", "null"),
            valid.replace("36.8151", "90.1"),
            valid.replace("127.1139", "180.1"),
            valid.replace("36.8151", "1e309"),
        )) {
            assertThrows("Accepted invalid coordinates: $json", IllegalArgumentException::class.java) {
                decode(json).toModel("trip-1")
            }
        }
    }

    @Test fun nullRecordedAtAndCoordinates_remainUnknown_withoutLegacyTimeFallback() {
        val response = decode("""{"id":"trip-1","tripId":"trip-1","savedStoryIds":["s"],"stories":[
          {"id":"s","title":"시간 없는 이야기","region":"천안","latitude":null,"longitude":null,
           "summary":"내용","highlights":[],"recordedAt":null,"createdAt":"2026-11-14T05:30:00Z"}
        ]}""")
        assertNull(response.toModel("trip-1").single().occurredAt)
    }

    @Test fun savedStories_useServerSelection_andSortChronologicallyWithoutInventingTimes() = runTest {
        val response = decode("""{
          "id":"trip-1",
          "savedStoryIds":["missing","later","first","created"],
          "stories":[
            {"id":"missing","title":"시간 없는 이야기","region":"부산"},
            {"id":"later","title":"나중 이야기","region":"부산","recordedAt":"2026-11-13T02:00:00Z"},
            {"id":"excluded","title":"저장 해제","region":"부산","saved":true},
            {"id":"first","title":"가장 이른 이야기","region":"창원","recordedAt":"2026-11-13T00:00:00Z",
             "summary":"가족과 바다에 가기로 했어요",
             "highlights":["엄마: 바다 보러 갈까?","아이: 좋아요!"]},
            {"id":"created","title":"등록 시간이 있는 이야기","region":"창원","recordedAt":"2026-11-13T01:00:00Z"}
          ]
        }""")

        val stories = TripSummaryRepository { response }.load("trip-1")

        assertEquals(listOf("first", "created", "later", "missing"), stories.map { it.key })
        assertEquals(stories.map { it.id }, stories.map { it.key })
        assertEquals(Instant.parse("2026-11-13T00:00:00Z"), stories.first().occurredAt)
        assertEquals(Instant.parse("2026-11-13T01:00:00Z"), stories[1].occurredAt)
        assertEquals("가족과 바다에 가기로 했어요", stories.first().content)
        assertEquals(listOf("엄마: 바다 보러 갈까?", "아이: 좋아요!"), stories.first().conversationExcerpts)
        assertNull(stories.last().occurredAt)
        assertNull(stories.last().content)
        assertTrue(stories.last().conversationExcerpts.isEmpty())
    }

    @Test fun equalAndMissingTimes_keepResponseOrder_andEmptySelectionOverridesSavedFlags() = runTest {
        val response = decode("""{
          "id":"trip-1","savedStoryIds":["b","missing-b","a","missing-a"],"stories":[
            {"id":"missing-a","title":"시간 없음 A","region":"서울"},
            {"id":"a","title":"A","region":"서울","recordedAt":"2026-11-13T01:00:00Z"},
            {"id":"b","title":"B","region":"서울","recordedAt":"2026-11-13T01:00:00Z"},
            {"id":"missing-b","title":"시간 없음 B","region":"서울"}
          ]
        }""")
        assertEquals(listOf("a", "b", "missing-a", "missing-b"), response.toModel("trip-1").map { it.key })
        assertTrue(response.copy(savedStoryIds = emptyList()).toModel("trip-1").isEmpty())
        val onlyFlag = decode("""{"id":"trip-1","savedStoryIds":[],"stories":[
          {"id":"flag","title":"명시 목록만 사용","region":"서울","saved":true}
        ]}""")
        assertTrue(onlyFlag.toModel("trip-1").isEmpty())
    }

    @Test fun malformedTripResponses_areRejectedBeforeAnyStoriesAreDisplayed() {
        val valid = """{"id":"trip-1","savedStoryIds":["s"],"stories":[{"id":"s","title":"제목","region":"서울"}]}"""
        val invalid = listOf(
            "{}",
            valid.replace("trip-1", "other-trip"),
            valid.replace("\"id\":\"trip-1\"", "\"tripId\":\"trip-1\""),
            valid.replace("\"savedStoryIds\":[\"s\"],", ""),
            valid.replace("\"title\":\"제목\"", "\"title\":\" \""),
            valid.replace("\"region\":\"서울\"", "\"region\":null"),
            valid.replace("[\"s\"]", "[\"unknown\"]"),
            valid.replace("[\"s\"]", "[\"s\",\"s\"]"),
            valid.replace("[\"s\"]", "[null]"),
            valid.replace("\"title\":\"제목\"", "\"title\":\"제목\",\"recordedAt\":\"yesterday\""),
            valid.replace("\"title\":\"제목\"", "\"title\":\"제목\",\"recordedAt\":\"2026-11-13 01:00:00\""),
            valid.replace("\"title\":\"제목\"", "\"title\":\"제목\",\"recordedAt\":\"2026-02-30T01:00:00Z\""),
            valid.replace("\"title\":\"제목\"", "\"title\":\"제목\",\"highlights\":[null]"),
            valid.replace("[{\"id\":\"s\",\"title\":\"제목\",\"region\":\"서울\"}]", "[null]"),
            valid.replace("\"stories\":[{\"id\":\"s\",\"title\":\"제목\",\"region\":\"서울\"}]", "\"stories\":[]"),
        )
        for (json in invalid) {
            assertThrows("Accepted invalid response: $json", IllegalArgumentException::class.java) {
                decode(json).toModel("trip-1")
            }
        }
        val response = decode(valid)
        assertThrows(IllegalArgumentException::class.java) {
            response.copy(stories = response.stories!! + response.stories!!).toModel("trip-1")
        }
    }

    @Test fun metadataGapsRemainEmpty_andIdsFollowTheMainNonblankContract() = runTest {
        val tripId = "여행 / " + "x".repeat(140)
        val storyId = "이야기 / " + "y".repeat(140)
        val response = TripSummaryResponseDto(tripId,
            listOf(TripSummaryStoryDto(storyId, "이야기", "서울", summary = "   ", highlights = listOf("", "  "))),
            listOf(storyId))
        val story = TripSummaryRepository { requested -> assertEquals(tripId, requested); response }.load(tripId).single()
        assertEquals(storyId, story.id)
        assertEquals(storyId, story.key)
        assertNull(story.occurredAt)
        assertNull(story.content)
        assertTrue(story.conversationExcerpts.isEmpty())
    }

    @Test fun blankTripId_isRejectedBeforeRequest_andUnconfiguredRepositoryFails() = runTest {
        var called = false
        val repository = TripSummaryRepository { called = true; TripSummaryResponseDto("trip-1", emptyList(), emptyList()) }
        try { repository.load(" "); fail("Blank ID was accepted") }
        catch (_: IllegalArgumentException) { }
        assertFalse(called)
        try { TripSummaryRepository().load("trip-1"); fail("Missing configuration was accepted") }
        catch (_: IOException) { }
    }

    @Test fun remoteRepository_rejectsUnsafeBaseUrls() {
        for (url in listOf("http://host/api/", "https:///api/", "https://user:secret@host/api/",
            "https://host/api/?query=1", "https://host/api/#fragment", "https://host/api")) {
            assertThrows("Accepted unsafe URL: $url", IllegalArgumentException::class.java) {
                TripSummaryRepository.remote(url)
            }
        }
    }

    private fun decode(json: String): TripSummaryResponseDto = Gson().fromJson(json, TripSummaryResponseDto::class.java)
}
