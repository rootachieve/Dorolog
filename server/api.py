from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response

from .database import (
    initialize_database,
    list_saved_story_ids,
    list_stories,
    replace_saved_story_ids,
    upsert_story,
)
from .llm import create_first_question, create_followup_question, recommend_topics
from .recording import (
    add_transcript,
    get_story_candidates,
    preview_current_story,
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
    TopicRecommendations,
    TopicRequest,
    TranscriptRequest,
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


@app.post("/topics", response_model=TopicRecommendations)
def topics(request: TopicRequest):
    return recommend_topics(request.destination_name)


@app.post("/questions/first", response_model=FirstQuestion)
def first_question(request: FirstQuestionRequest):
    return create_first_question(request.destination, request.topic)


@app.post("/questions/follow-up", response_model=FollowupSuggestion)
def followup_question(request: FollowupQuestionRequest):
    return create_followup_question(request.topic, request.recent_conversation)


@app.post("/recordings")
def create_recording(request: RecordingStartRequest):
    return start_recording(request.destination, request.topic)


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
    return {
        "stories": list_stories(trip_id),
        "savedStoryIds": list_saved_story_ids(trip_id),
    }


@app.put("/api/trips/{trip_id}/saved-stories", status_code=204)
def save_trip_stories(trip_id: str, request: SavedStoriesRequest):
    replace_saved_story_ids(trip_id, request.story_ids)
    return Response(status_code=204)
