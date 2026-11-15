import asyncio
import json
import logging
from datetime import datetime
from uuid import uuid4

from fastapi import WebSocket
from pydantic import ValidationError

from ..ai.llm import create_followup_question
from ..recording.database import ensure_trip
from ..recording.recording import (
    add_transcript,
    get_recent_conversation,
    get_unorganized_transcript_count,
    save_pending_stories,
    start_recording,
)
from ..schemas import (
    ConversationLocationMessage,
    ConversationResponseCancelMessage,
    ConversationResponseCreateMessage,
    ConversationStartMessage,
)
from .transcription import (
    open_transcription,
    receive_transcription_event,
    send_transcription_audio,
)
from .tts import stream_speech_pcm


CONTROL_MESSAGE_MAX_CHARS = 16_384
PCM_MESSAGE_MAX_BYTES = 24_000
PCM_BYTES_PER_SECOND = 24_000 * 2

logger = logging.getLogger(__name__)


async def close_with_error(websocket: WebSocket, message: str, code: int = 1008) -> None:
    await websocket.send_json({"type": "error", "message": message})
    await websocket.close(code=code)


def read_control_message(message: dict) -> str:
    text = message.get("text")
    if text is None or len(text) > CONTROL_MESSAGE_MAX_CHARS:
        raise ValueError("제어 메시지 형식이 올바르지 않습니다.")
    return text


async def send_speech_audio(websocket: WebSocket, text: str) -> None:
    """Android 재생 버퍼가 넘치지 않도록 PCM을 실제 재생 속도로 보낸다."""
    loop = asyncio.get_running_loop()
    started_at = None
    sent_bytes = 0

    async for pcm in stream_speech_pcm(text):
        if started_at is None:
            started_at = loop.time()

        await websocket.send_bytes(pcm)
        sent_bytes += len(pcm)

        playback_time = sent_bytes / PCM_BYTES_PER_SECOND
        remaining_time = started_at + playback_time - loop.time()
        if remaining_time > 0:
            await asyncio.sleep(remaining_time)


async def save_story_batch(state: dict) -> None:
    """새로 누적된 대화를 정리해 SQLite에 저장한다."""
    success = False

    try:
        stories = await asyncio.to_thread(
            save_pending_stories,
            state["session_id"],
            state["trip_id"],
        )
        success = True
        if stories:
            logger.info(
                "stories saved: session_id=%s count=%d",
                state["session_id"],
                len(stories),
            )
    except Exception as error:
        logger.warning("story organization failed: %s", error)
    finally:
        state["story_task"] = None

        if success and state["connection_open"]:
            if state["story_save_requested"]:
                schedule_story_save(state)


def schedule_story_save(state: dict) -> None:
    """30초 무음 요청이 오면 아직 정리하지 않은 대화를 저장한다."""
    state["story_save_requested"] = True

    pending_count = get_unorganized_transcript_count(state["session_id"])
    if pending_count == 0:
        state["story_save_requested"] = False
        return

    story_task = state.get("story_task")
    if story_task is not None and not story_task.done():
        return

    state["story_save_requested"] = False
    state["story_task"] = asyncio.create_task(save_story_batch(state))


async def create_followup_response(
    websocket: WebSocket,
    session_id: str,
    topic: str,
    state: dict,
) -> None:
    """최근 대화를 반영한 후속 질문을 Android에 보낸다."""
    response_id = uuid4().hex
    state["response_id"] = response_id

    try:
        await websocket.send_json(
            {
                "type": "response.started",
                "response_id": response_id,
            }
        )

        recent_conversation = get_recent_conversation(session_id)
        if recent_conversation:
            suggestion = await asyncio.to_thread(
                create_followup_question,
                topic,
                recent_conversation,
            )
            if suggestion.should_suggest and suggestion.question:
                await websocket.send_json(
                    {
                        "type": "response.text",
                        "response_id": response_id,
                        "text": suggestion.question,
                    }
                )
                await send_speech_audio(websocket, suggestion.question)

        await websocket.send_json(
            {
                "type": "response.done",
                "response_id": response_id,
            }
        )
    except asyncio.CancelledError:
        return
    except Exception as error:
        logger.warning("follow-up response failed: %s", error)
        if state.get("response_id") == response_id:
            await websocket.send_json(
                {
                    "type": "response.done",
                    "response_id": response_id,
                }
            )
    finally:
        if state.get("response_id") == response_id:
            state["response_id"] = None
            state["response_task"] = None


async def receive_android_messages(
    websocket: WebSocket,
    transcription,
    start: ConversationStartMessage,
    state: dict,
) -> None:
    """Android의 PCM과 위치 변경을 받아 STT 연결에 전달한다."""
    while True:
        message = await websocket.receive()
        if message["type"] == "websocket.disconnect":
            return

        pcm = message.get("bytes")
        if pcm is not None:
            if len(pcm) == 0 or len(pcm) > PCM_MESSAGE_MAX_BYTES or len(pcm) % 2 != 0:
                await close_with_error(
                    websocket,
                    "PCM 오디오 형식을 확인해 주세요.",
                    code=1003,
                )
                return
            await send_transcription_audio(transcription, pcm)
            state["pcm_bytes_received"] += len(pcm)
            continue

        try:
            text = read_control_message(message)
            message_type = json.loads(text).get("type")
        except (ValueError, json.JSONDecodeError, AttributeError):
            await close_with_error(websocket, "제어 메시지를 확인해 주세요.")
            return

        if message_type == "location.changed":
            try:
                location = ConversationLocationMessage.model_validate_json(text)
            except ValidationError:
                await close_with_error(websocket, "location.changed 메시지를 확인해 주세요.")
                return

            if location.trip_id != start.trip_id:
                await close_with_error(websocket, "연결된 여행 ID와 위치 메시지의 여행 ID가 다릅니다.")
                return

            state["current_address"] = location.current_address
            state["latitude"] = location.latitude
            state["longitude"] = location.longitude
            continue

        if message_type == "response.create":
            try:
                ConversationResponseCreateMessage.model_validate_json(text)
            except ValidationError:
                await close_with_error(websocket, "response.create 메시지를 확인해 주세요.")
                return

            if state["speech_active"]:
                await websocket.send_json({"type": "input.speech_started"})
                continue

            schedule_story_save(state)

            response_task = state.get("response_task")
            if response_task is not None and not response_task.done():
                continue

            state["response_task"] = asyncio.create_task(
                create_followup_response(
                    websocket,
                    state["session_id"],
                    start.question,
                    state,
                )
            )
            continue

        if message_type == "response.cancel":
            try:
                ConversationResponseCancelMessage.model_validate_json(text)
            except ValidationError:
                await close_with_error(websocket, "response.cancel 메시지를 확인해 주세요.")
                return

            response_task = state.get("response_task")
            if response_task is not None and not response_task.done():
                response_task.cancel()
            state["response_task"] = None
            state["response_id"] = None
            continue

        await close_with_error(websocket, "아직 지원하지 않는 제어 메시지입니다.")
        return


async def receive_stt_events(
    websocket: WebSocket,
    transcription,
    session_id: str,
    state: dict,
) -> None:
    """OpenAI의 VAD 상태를 Android에 알리고 완성된 텍스트를 기록한다."""
    while True:
        event = await receive_transcription_event(transcription)
        event_type = event.get("type")

        if event_type == "input_audio_buffer.speech_started":
            state["speech_active"] = True
            item_id = event.get("item_id")
            if item_id:
                state["speech_contexts"][item_id] = {
                    "location": state["current_address"] or "위치 정보 없음",
                    "recorded_at": datetime.now().astimezone(),
                    "latitude": state["latitude"],
                    "longitude": state["longitude"],
                }
            await websocket.send_json({"type": "input.speech_started"})
            continue

        if event_type == "input_audio_buffer.speech_stopped":
            state["speech_active"] = False
            await websocket.send_json({"type": "input.speech_stopped"})
            continue

        if event_type == "conversation.item.input_audio_transcription.completed":
            transcript = event.get("transcript", "").strip()
            if not transcript:
                continue

            item_id = event.get("item_id")
            context = state["speech_contexts"].pop(
                item_id,
                {
                    "location": state["current_address"] or "위치 정보 없음",
                    "recorded_at": datetime.now().astimezone(),
                    "latitude": state["latitude"],
                    "longitude": state["longitude"],
                },
            )
            add_transcript(
                session_id,
                transcript,
                context["location"],
                context["recorded_at"],
                context["latitude"],
                context["longitude"],
            )
            logger.info(
                "transcription completed: session_id=%s chars=%d",
                session_id,
                len(transcript),
            )
            continue

        if event_type == "conversation.item.input_audio_transcription.failed":
            raise RuntimeError("음성을 텍스트로 변환하지 못했습니다.")

        if event_type == "error":
            error = event.get("error", {})
            raise RuntimeError(error.get("message", "STT 서버 오류가 발생했습니다."))


async def run_conversation(websocket: WebSocket) -> None:
    """Android 음성을 OpenAI VAD·STT에 연결한다."""
    await websocket.accept()

    first_message = await websocket.receive()
    if first_message["type"] == "websocket.disconnect":
        return

    try:
        start = ConversationStartMessage.model_validate_json(
            read_control_message(first_message)
        )
    except (ValidationError, ValueError, json.JSONDecodeError):
        await close_with_error(websocket, "session.start 메시지를 확인해 주세요.")
        return

    ensure_trip(
        trip_id=start.trip_id,
        origin_name=start.current_address or "위치 정보 없음",
        origin_latitude=start.latitude,
        origin_longitude=start.longitude,
        destination_name=start.destination_name,
    )
    recording = start_recording(
        destination=start.destination_name or "",
        topic=start.question,
        recording_id=start.session_id,
        trip_id=start.trip_id,
    )
    session_id = recording["recording_id"]
    state = {
        "session_id": session_id,
        "trip_id": start.trip_id,
        "current_address": start.current_address,
        "latitude": start.latitude,
        "longitude": start.longitude,
        "pcm_bytes_received": 0,
        "speech_contexts": {},
        "speech_active": False,
        "response_id": None,
        "response_task": None,
        "story_task": None,
        "story_save_requested": False,
        "connection_open": True,
    }

    try:
        async with open_transcription() as transcription:
            await websocket.send_json(
                {
                    "type": "session.ready",
                    "session_id": session_id,
                }
            )

            android_task = asyncio.create_task(
                receive_android_messages(websocket, transcription, start, state)
            )
            stt_task = asyncio.create_task(
                receive_stt_events(websocket, transcription, session_id, state)
            )
            done, pending = await asyncio.wait(
                {android_task, stt_task},
                return_when=asyncio.FIRST_COMPLETED,
            )

            for task in pending:
                task.cancel()
            await asyncio.gather(*pending, return_exceptions=True)

            for task in done:
                error = task.exception()
                if error is not None:
                    raise error
    except asyncio.CancelledError:
        return
    except Exception as error:
        logger.warning("conversation STT failed: %s", error)
        await close_with_error(websocket, "음성 인식 서버에 연결할 수 없어요.")
        return
    finally:
        state["connection_open"] = False

        response_task = state.get("response_task")
        if response_task is not None and not response_task.done():
            response_task.cancel()
            await asyncio.gather(response_task, return_exceptions=True)

        story_task = state.get("story_task")
        if story_task is not None and not story_task.done():
            await asyncio.gather(story_task, return_exceptions=True)

        if get_unorganized_transcript_count(session_id) > 0:
            await save_story_batch(state)

    logger.info(
        "conversation closed: session_id=%s trip_id=%s pcm_bytes=%d",
        session_id,
        start.trip_id,
        state["pcm_bytes_received"],
    )
