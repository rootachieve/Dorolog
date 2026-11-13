from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Query, Request, WebSocket
from fastapi.responses import JSONResponse, Response

from .recording.database import (
    create_trip,
    get_story_map,
    get_trip,
    initialize_database,
    list_recent_trips,
    list_saved_story_ids,
    list_stories,
    mark_trip_finished,
    replace_saved_story_ids,
    resume_trip,
    upsert_story,
)
from .voice.conversation import run_conversation
from .ai.llm import (
    create_first_question,
    create_followup_question,
    recommend_topics,
)
from .recording.recording import (
    add_transcript,
    finish_trip_recordings,
    get_story_candidates,
    preview_current_story,
    save_trip_pending_stories,
    start_recording,
    stop_recording,
)
from .schemas import (
    ConversationSummary,
    CurrentStoryTitle,
    FirstQuestion,
    FirstQuestionRequest,
    FollowupQuestionRequest,
    FollowupSuggestion,
    RecordingStartRequest,
    SavedStoriesRequest,
    StoryMapResponse,
    TopicRecommendations,
    TopicRequest,
    TranscriptRequest,
    TripCreateRequest,
    TripDetailResponse,
    RecentTripsResponse,
    TripResponse,
    TripStoriesResponse,
    TripStoryUpsertRequest,
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    initialize_database()
    yield


app = FastAPI(title="Dorolog AI Server", lifespan=lifespan)


@app.exception_handler(KeyError)
async def handle_missing_recording(request: Request, error: KeyError):
    return JSONResponse(
        status_code=404,
        content={"detail": "기록을 찾을 수 없습니다."},
    )


@app.exception_handler(ValueError)
async def handle_invalid_recording_state(request: Request, error: ValueError):
    return JSONResponse(
        status_code=409,
        content={"detail": str(error)},
    )


@app.get("/health")
def health():
    return {"status": "ok"}


@app.websocket("/conversation")
async def conversation(websocket: WebSocket):
    await run_conversation(websocket)


@app.post("/api/topics", response_model=TopicRecommendations)
@app.post("/topics", response_model=TopicRecommendations)
def topics(request: TopicRequest):
    return recommend_topics(request.destination_name)


@app.post(
    "/api/trips",
    response_model=TripResponse,
    status_code=201,
)
def create_new_trip(request: TripCreateRequest):
    return create_trip(
        origin_name=request.origin_name,
        origin_latitude=request.origin_latitude,
        origin_longitude=request.origin_longitude,
        destination_name=request.destination_name,
        destination_latitude=request.destination_latitude,
        destination_longitude=request.destination_longitude,
    )


@app.get("/api/trips", response_model=RecentTripsResponse)
def recent_trips(limit: int = Query(default=6, ge=1, le=50)):
    return {"trips": list_recent_trips(limit)}


@app.get("/api/stories/map", response_model=StoryMapResponse)
def story_map():
    return get_story_map()


@app.get("/api/trips/{trip_id}", response_model=TripDetailResponse)
def trip_detail(trip_id: str):
    trip = get_trip(trip_id)
    if trip is None:
        raise HTTPException(status_code=404, detail="여행을 찾을 수 없습니다.")

    return {
        **trip,
        "stories": list_stories(trip_id),
        "savedStoryIds": list_saved_story_ids(trip_id),
    }


@app.post("/api/trips/{trip_id}/resume", response_model=TripResponse)
def continue_trip(trip_id: str):
    trip = resume_trip(trip_id)
    if trip is None:
        raise HTTPException(status_code=404, detail="여행을 찾을 수 없습니다.")
    return trip


@app.post("/questions/first", response_model=FirstQuestion)
def first_question(request: FirstQuestionRequest):
    return create_first_question(request.destination, request.topic)


@app.post("/questions/follow-up", response_model=FollowupSuggestion)
def followup_question(request: FollowupQuestionRequest):
    return create_followup_question(request.topic, request.recent_conversation)


@app.post("/recordings")
def create_recording(request: RecordingStartRequest):
    return start_recording(
        request.destination,
        request.topic,
        trip_id=request.trip_id,
    )


@app.post(
    "/recordings/{recording_id}/transcripts",
    response_model=TranscriptRequest,
)
def append_transcript(recording_id: str, request: TranscriptRequest):
    return add_transcript(
        recording_id,
        request.text,
        request.location,
        request.recorded_at,
        request.latitude,
        request.longitude,
    )


@app.post("/recordings/{recording_id}/preview", response_model=CurrentStoryTitle)
def preview_story(recording_id: str):
    return preview_current_story(recording_id)


@app.post(
    "/recordings/{recording_id}/stories",
    response_model=ConversationSummary,
)
def story_candidates(recording_id: str):
    return get_story_candidates(recording_id)


@app.post("/recordings/{recording_id}/stop")
def finish_recording(recording_id: str):
    return stop_recording(recording_id)


@app.put("/api/trips/{trip_id}/stories/{story_id}", status_code=204)
def register_trip_story(
    trip_id: str,
    story_id: str,
    request: TripStoryUpsertRequest,
):
    upsert_story(
        trip_id=trip_id,
        story_id=story_id,
        title=request.title,
        region=request.region,
        latitude=request.latitude,
        longitude=request.longitude,
        summary=request.summary,
        highlights=request.highlights,
        recorded_at=(
            request.recorded_at.isoformat()
            if request.recorded_at is not None
            else None
        ),
    )
    return Response(status_code=204)


@app.get(
    "/api/trips/{trip_id}/stories",
    response_model=TripStoriesResponse,
)
def get_trip_stories(trip_id: str):
    # Android는 정차 화면에 들어오자마자 이 API를 호출한다.
    # 먼저 30초를 채우지 못한 짧은 대화까지 정리한 뒤 완성된 목록을 반환한다.
    save_trip_pending_stories(trip_id)
    return {
        "stories": list_stories(trip_id),
        "savedStoryIds": list_saved_story_ids(trip_id),
    }


@app.post(
    "/api/trips/{trip_id}/finish",
    response_model=TripStoriesResponse,
)
def finish_trip(trip_id: str):
    finish_trip_recordings(trip_id)
    if mark_trip_finished(trip_id) is None:
        raise HTTPException(status_code=404, detail="여행을 찾을 수 없습니다.")
    return {
        "stories": list_stories(trip_id),
        "savedStoryIds": list_saved_story_ids(trip_id),
    }


@app.put("/api/trips/{trip_id}/saved-stories", status_code=204)
def save_trip_stories(trip_id: str, request: SavedStoriesRequest):
    replace_saved_story_ids(trip_id, request.story_ids)
    return Response(status_code=204)
