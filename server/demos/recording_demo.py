import json
from datetime import datetime

from ..recording.recording import (
    add_transcript,
    get_story_candidates,
    start_recording,
    stop_recording,
)


recording = start_recording(
    destination="부산 할머니댁",
    topic="부산에서 가족과 함께 보내고 싶은 하루.",
)
recording_id = recording["recording_id"]

print("기록 시작")
print(f"기록 ID: {recording_id}")

add_transcript(
    recording_id,
    "광안리에서 다 같이 자전거 타고 싶어.",
    "천안",
    datetime.fromisoformat("2026-11-14T14:30:00+09:00"),
)
add_transcript(
    recording_id,
    "예전에 제주도에서 같이 탔을 때가 생각나네.",
    "천안",
    datetime.fromisoformat("2026-11-14T14:31:00+09:00"),
)

print("\n정차 중 이야기 후보 생성...")
story_candidates = get_story_candidates(recording_id)
print(story_candidates.model_dump_json(indent=2, by_alias=True))

add_transcript(
    recording_id,
    "천안 휴게소에서 먹었던 호두과자가 기억나.",
    "대전 부근",
    datetime.fromisoformat("2026-11-14T15:20:00+09:00"),
)
add_transcript(
    recording_id,
    "다음에는 천안 독립기념관에도 가보고 싶어.",
    "대전 부근",
    datetime.fromisoformat("2026-11-14T15:21:00+09:00"),
)

print("\n목적지 도착 후 기록 종료...")
finished_recording = stop_recording(recording_id)

print("\n최종 기록")
print(json.dumps(finished_recording, ensure_ascii=False, indent=2))
