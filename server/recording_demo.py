import json

from .recording import (
    add_transcript,
    save_current_story,
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

add_transcript(recording_id, "광안리에서 다 같이 자전거 타고 싶어.")
add_transcript(recording_id, "예전에 제주도에서 같이 탔을 때가 생각나네.")

print("\n첫 번째 이야기 저장 중...")
first_stories = save_current_story(recording_id)
print(json.dumps(first_stories, ensure_ascii=False, indent=2))

add_transcript(recording_id, "천안 휴게소에서 먹었던 호두과자가 기억나.")
add_transcript(recording_id, "다음에는 천안 독립기념관에도 가보고 싶어.")

print("\n기록 종료 중...")
finished_recording = stop_recording(recording_id)

print("\n최종 기록")
print(json.dumps(finished_recording, ensure_ascii=False, indent=2))
