from datetime import datetime
from uuid import uuid4

from .llm import create_current_story_title, organize_conversation
from .schemas import ConversationSummary, CurrentStoryTitle


# MVP에서는 실행 중인 기록을 메모리에 보관한다.
# 서버가 종료되면 이 데이터도 사라지며, DB 저장은 API 연결 이후에 추가한다.
recordings: dict[str, dict] = {}


def start_recording(destination: str, topic: str) -> dict:
    """새로운 여정 기록을 만들고 기록 ID를 발급한다."""
    recording_id = uuid4().hex
    recording = {
        "recording_id": recording_id,
        "destination": destination,
        "topic": topic,
        "status": "recording",
        "started_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "ended_at": "",
        "pending_transcript": [],
        "saved_stories": [],
    }
    recordings[recording_id] = recording

    return recording


def add_transcript(recording_id: str, text: str) -> dict:
    """음성 인식으로 들어온 발화 한 문장을 현재 기록에 추가한다."""
    recording = recordings[recording_id]
    if recording["status"] != "recording":
        raise ValueError("이미 종료된 기록에는 대화를 추가할 수 없습니다.")

    transcript = {"text": text}
    recording["pending_transcript"].append(transcript)

    return transcript


def preview_current_story(recording_id: str) -> CurrentStoryTitle:
    """아직 저장하지 않은 대화의 현재 제목을 만들되 기록 상태는 변경하지 않는다."""
    recording = recordings[recording_id]
    if recording["status"] != "recording":
        raise ValueError("이미 종료된 기록은 미리 볼 수 없습니다.")

    if not recording["pending_transcript"]:
        return CurrentStoryTitle(title="")

    conversation = "\n".join(
        item["text"] for item in recording["pending_transcript"]
    )

    return create_current_story_title(conversation)


def get_story_candidates(recording_id: str) -> ConversationSummary:
    """정차 중 고를 이야기 후보를 만들되 기록을 저장하거나 비우지 않는다."""
    recording = recordings[recording_id]
    if recording["status"] != "recording":
        raise ValueError("이미 종료된 기록에서는 이야기 후보를 만들 수 없습니다.")

    if not recording["pending_transcript"]:
        return ConversationSummary(stories=[])

    conversation = "\n".join(
        item["text"] for item in recording["pending_transcript"]
    )

    return organize_conversation(conversation)


def save_current_story(recording_id: str) -> list[dict]:
    """아직 저장하지 않은 대화를 이야기로 정리하고 기록에 저장한다."""
    recording = recordings[recording_id]
    if recording["status"] != "recording":
        raise ValueError("이미 종료된 기록은 다시 저장할 수 없습니다.")

    if not recording["pending_transcript"]:
        return []

    conversation = "\n".join(
        item["text"] for item in recording["pending_transcript"]
    )
    summary = organize_conversation(conversation)
    new_stories = [story.model_dump() for story in summary.stories]

    recording["saved_stories"].extend(new_stories)
    recording["pending_transcript"].clear()

    return new_stories


def stop_recording(recording_id: str) -> dict:
    """남은 대화를 저장한 뒤 현재 여정 기록을 종료한다."""
    recording = recordings[recording_id]

    if recording["pending_transcript"]:
        save_current_story(recording_id)

    recording["status"] = "stopped"
    recording["ended_at"] = datetime.now().astimezone().isoformat(timespec="seconds")

    return recording
