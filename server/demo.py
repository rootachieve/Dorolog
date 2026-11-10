from time import perf_counter

from .llm import (
    create_first_question,
    create_followup_question,
    organize_conversation,
    recommend_topics,
)


destination = input("목적지: ").strip()

started_at = perf_counter()
topic_result = recommend_topics(destination)
topic_elapsed = perf_counter() - started_at

print(f"\n추천 주제 ({topic_elapsed:.2f}초)")
for number, topic in enumerate(topic_result.topics, start=1):
    print(f"{number}. {topic}")

selected_number = int(input("\n주제 선택 (1 또는 2): "))
selected_topic = topic_result.topics[selected_number - 1]

started_at = perf_counter()
question_result = create_first_question(destination, selected_topic)
question_elapsed = perf_counter() - started_at

print(f"\n첫 질문 ({question_elapsed:.2f}초)")
print(question_result.question)

recent_conversation = input("\n최근 대화: ").strip()

started_at = perf_counter()
followup_result = create_followup_question(selected_topic, recent_conversation)
followup_elapsed = perf_counter() - started_at

if followup_result.should_suggest:
    print(f"\n이어갈 질문 ({followup_elapsed:.2f}초)")
    print(followup_result.question)
else:
    print(f"\n이어갈 질문 없음 ({followup_elapsed:.2f}초)")

started_at = perf_counter()
summary_result = organize_conversation(recent_conversation)
summary_elapsed = perf_counter() - started_at

print(f"\n대화 정리 ({summary_elapsed:.2f}초)")
if not summary_result.stories:
    print("정리할 이야기가 없습니다.")

for number, story in enumerate(summary_result.stories, start=1):
    print(f"\n{number}. {story.title}")
    print(f"요약: {story.summary}")
    print(f"참여자: {', '.join(story.speakers)}")

    for place in story.wanted_places:
        print(f"가보고 싶은 곳: {place.name} · {place.speaker} · {place.reason}")

