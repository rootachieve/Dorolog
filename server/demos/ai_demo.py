from datetime import datetime
from time import perf_counter

from ..ai.llm import (
    create_followup_question,
    organize_conversation,
    recommend_topics,
)


destination_input = input("목적지 (없으면 Enter): ").strip()
destination = destination_input or None

started_at = perf_counter()
topic_result = recommend_topics(destination)
topic_elapsed = perf_counter() - started_at

print(f"\n추천 주제 ({topic_elapsed:.2f}초)")
for number, topic in enumerate(topic_result.topics, start=1):
    print(f"{number}. {topic.question}")

selected_number = int(input("\n질문 선택 (1~4): "))
selected_question = topic_result.topics[selected_number - 1].question

print("\n첫 질문")
print(selected_question)

recent_conversation = input("\n최근 대화: ").strip()
current_location = input("현재 지역: ").strip()

started_at = perf_counter()
followup_result = create_followup_question(selected_question, recent_conversation)
followup_elapsed = perf_counter() - started_at

if followup_result.should_suggest:
    print(f"\n이어갈 질문 ({followup_elapsed:.2f}초)")
    print(followup_result.question)
else:
    print(f"\n이어갈 질문 없음 ({followup_elapsed:.2f}초)")

started_at = perf_counter()
recorded_at = datetime.now().astimezone().isoformat(timespec="seconds")
conversation_with_location = (
    f"[{recorded_at} · {current_location}] {recent_conversation}"
)
summary_result = organize_conversation(conversation_with_location)
summary_elapsed = perf_counter() - started_at

print(f"\n대화 정리 ({summary_elapsed:.2f}초)")
if not summary_result.stories:
    print("정리할 이야기가 없습니다.")

for number, story in enumerate(summary_result.stories, start=1):
    print(f"\n{number}. {story.title}")
    print(f"기록 위치: {story.recorded_at.isoformat()} · {story.location}")
    print(f"요약: {story.summary}")
    for highlight in story.highlights:
        print(f"핵심 대화: {highlight}")

