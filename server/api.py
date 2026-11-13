from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

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
    TopicRecommendations,
    TopicRequest,
    TranscriptRequest,
)


app = FastAPI(title="Dorolog AI Server")


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


@app.post("/recordings/{recording_id}/transcripts")
def append_transcript(recording_id: str, request: TranscriptRequest):
    return add_transcript(recording_id, request.text)


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
