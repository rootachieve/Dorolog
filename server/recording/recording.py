from datetime import datetime
from pathlib import Path
from threading import RLock
from typing import Optional
from uuid import NAMESPACE_URL, uuid4, uuid5

from ..ai.llm import create_current_story_title, organize_conversation
from ..schemas import ConversationSummary, CurrentStoryTitle
from .database import (
    DATABASE_PATH,
    list_saved_story_ids,
    replace_saved_story_ids,
    upsert_story,
)


# 실행 중인 원본 대화는 메모리에 두고, AI가 정리한 이야기는 SQLite에 저장한다.
recordings: dict[str, dict] = {}
recording_locks: dict[str, RLock] = {}


def start_recording(
    destination: str,
    topic: str,
    recording_id: Optional[str] = None,
    trip_id: str = "",
) -> dict:
    """새로운 여정 기록을 만들고 기록 ID를 발급한다."""
    recording_id = recording_id or uuid4().hex
    if recording_id in recordings:
        recording_locks.setdefault(recording_id, RLock())
        if trip_id and not recordings[recording_id]["trip_id"]:
            recordings[recording_id]["trip_id"] = trip_id
        return recordings[recording_id]

    recording = {
        "recording_id": recording_id,
        "trip_id": trip_id,
        "destination": destination,
        "topic": topic,
        "status": "recording",
        "started_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "ended_at": "",
        "pending_transcript": [],
        "organized_transcript_count": 0,
        "generated_story_ids": [],
    }
    recordings[recording_id] = recording
    recording_locks[recording_id] = RLock()

    return recording


def add_transcript(
    recording_id: str,
    text: str,
    location: str,
    recorded_at: datetime,
    latitude: Optional[float] = None,
    longitude: Optional[float] = None,
) -> dict:
    """음성 인식으로 들어온 발화 한 문장을 현재 기록에 추가한다."""
    lock = recording_locks.setdefault(recording_id, RLock())
    with lock:
        recording = recordings[recording_id]
        if recording["status"] != "recording":
            raise ValueError("이미 종료된 기록에는 대화를 추가할 수 없습니다.")

        transcript = {
            "text": text,
            "location": location,
            "recorded_at": recorded_at.isoformat(),
            "latitude": latitude,
            "longitude": longitude,
        }
        recording["pending_transcript"].append(transcript)

    return transcript


def get_recent_conversation(recording_id: str) -> str:
    """후속 질문에 사용할 최근 대화 최대 10개를 반환한다."""
    recording = recordings[recording_id]
    recent_transcript = recording["pending_transcript"][-10:]
    return "\n".join(item["text"] for item in recent_transcript)


def get_unorganized_transcript_count(recording_id: str) -> int:
    """아직 이야기로 정리하지 않은 발화 수를 반환한다."""
    recording = recordings[recording_id]
    return (
        len(recording["pending_transcript"])
        - recording["organized_transcript_count"]
    )


def save_pending_stories(
    recording_id: str,
    trip_id: str = "",
    database_path: Path = DATABASE_PATH,
) -> list[dict]:
    """새로 누적된 대화만 이야기로 정리해 SQLite에 저장한다."""
    lock = recording_locks.setdefault(recording_id, RLock())

    # 30초 무음 처리와 정차 화면 조회가 동시에 들어와도 같은 발화를 두 번 정리하지 않는다.
    with lock:
        recording = recordings[recording_id]
        trip_id = trip_id or recording["trip_id"]
        if not trip_id:
            raise ValueError("이야기를 저장하려면 여행 ID가 필요합니다.")

        start_index = recording["organized_transcript_count"]
        end_index = len(recording["pending_transcript"])
        transcripts = recording["pending_transcript"][start_index:end_index]
        if not transcripts:
            return []

        conversation = "\n".join(
            f'[{item["recorded_at"]} · {item["location"]}] {item["text"]}'
            for item in transcripts
        )
        summary = organize_conversation(conversation)
        saved_stories = []

        for story_index, story in enumerate(summary.stories):
            metadata = next(
                (
                    item
                    for item in transcripts
                    if datetime.fromisoformat(item["recorded_at"]) == story.recorded_at
                ),
                transcripts[0],
            )
            story_id = uuid5(
                NAMESPACE_URL,
                f"dorolog:{recording_id}:{start_index}:{story_index}",
            ).hex

            upsert_story(
                trip_id=trip_id,
                story_id=story_id,
                title=story.title,
                region=metadata["location"],
                latitude=metadata["latitude"],
                longitude=metadata["longitude"],
                summary=story.summary,
                highlights=story.highlights,
                recorded_at=metadata["recorded_at"],
                database_path=database_path,
            )
            saved_stories.append(
                {
                    "id": story_id,
                    "title": story.title,
                    "region": metadata["location"],
                    "latitude": metadata["latitude"],
                    "longitude": metadata["longitude"],
                    "summary": story.summary,
                    "highlights": story.highlights,
                    "recordedAt": metadata["recorded_at"],
                }
            )

        if saved_stories:
            selected_ids = set(list_saved_story_ids(trip_id, database_path))
            selected_ids.update(story["id"] for story in saved_stories)
            replace_saved_story_ids(trip_id, sorted(selected_ids), database_path)

        recording["organized_transcript_count"] = end_index
        for story in saved_stories:
            if story["id"] not in recording["generated_story_ids"]:
                recording["generated_story_ids"].append(story["id"])

        return saved_stories


def save_trip_pending_stories(
    trip_id: str,
    database_path: Path = DATABASE_PATH,
) -> list[dict]:
    """정차 화면 조회 전에 해당 여행의 아직 정리하지 않은 대화를 모두 저장한다."""
    saved_stories = []
    recording_ids = [
        recording_id
        for recording_id, recording in recordings.items()
        if recording["trip_id"] == trip_id
        and get_unorganized_transcript_count(recording_id) > 0
    ]

    for recording_id in recording_ids:
        saved_stories.extend(
            save_pending_stories(recording_id, trip_id, database_path)
        )

    return saved_stories


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
    """정차 중이나 기록 종료 후 고를 이야기 후보를 만든다."""
    recording = recordings[recording_id]

    if not recording["pending_transcript"]:
        return ConversationSummary(stories=[])

    conversation = "\n".join(
        f'[{item["recorded_at"]} · {item["location"]}] {item["text"]}'
        for item in recording["pending_transcript"]
    )

    return organize_conversation(conversation)


def stop_recording(recording_id: str) -> dict:
    """대화 내용은 그대로 둔 채 현재 여정 기록을 종료한다."""
    lock = recording_locks.setdefault(recording_id, RLock())
    with lock:
        recording = recordings[recording_id]

        recording["status"] = "stopped"
        recording["ended_at"] = datetime.now().astimezone().isoformat(timespec="seconds")

    return recording


def finish_trip_recordings(
    trip_id: str,
    database_path: Path = DATABASE_PATH,
) -> list[dict]:
    """여행의 남은 대화를 저장한 뒤 모든 기록을 종료한다."""
    if not trip_id.strip():
        raise ValueError("여행 ID가 필요합니다.")

    saved_stories = []
    recording_ids = [
        recording_id
        for recording_id, recording in recordings.items()
        if recording["trip_id"] == trip_id
    ]

    for recording_id in recording_ids:
        lock = recording_locks.setdefault(recording_id, RLock())
        with lock:
            saved_stories.extend(
                save_pending_stories(recording_id, trip_id, database_path)
            )
            stop_recording(recording_id)

    return saved_stories
