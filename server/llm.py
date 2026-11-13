import os
from typing import Optional

from dotenv import load_dotenv
from openai import OpenAI

from .schemas import (
    ConversationSummary,
    CurrentStoryTitle,
    FirstQuestion,
    FollowupSuggestion,
    RecommendedTopic,
    TopicQuestions,
    TopicRecommendations,
)


load_dotenv()

client = OpenAI()
MODEL = os.getenv("OPENAI_MODEL", "gpt-6.1-sol")


def recommend_topics(destination: Optional[str]) -> TopicRecommendations:
    """목적지 질문 2개와 가족 여행 질문 2개를 만든다."""
    destination_text = destination.strip() if destination else "없음"
    response = client.responses.parse(
        model=MODEL,
        input=[
            {
                "role": "system",
                "content": (
                    "당신은 가족의 차량 이동 중 대화를 돕는 진행자입니다. "
                    "가족 모두가 답할 수 있는 서로 다른 질문을 정확히 4개 만드세요. "
                    "1번과 2번은 입력된 목적지에서 기대하는 활동, 음식이나 경험을 물으세요. "
                    "목적지가 없으면 특정 장소를 지어내지 말고 이번 이동 중 함께 하고 싶은 일이나 기대를 물으세요. "
                    "3번과 4번은 특정 목적지와 관계없는 가족 여행의 추억이나 경험을 물으세요. "
                    "예나 아니오로 끝나거나 운전자를 지목하는 질문은 피하고, "
                    "각 질문을 한 문장으로 작성해 물음표로 끝내세요."
                ),
            },
            {
                "role": "user",
                "content": f"목적지: {destination_text}",
            },
        ],
        text_format=TopicQuestions,
    )

    topic_ids = (
        "destination_1",
        "destination_2",
        "family_travel_1",
        "family_travel_2",
    )
    topics = [
        RecommendedTopic(id=topic_id, question=question)
        for topic_id, question in zip(topic_ids, response.output_parsed.questions)
    ]

    return TopicRecommendations(topics=topics)


def create_first_question(destination: str, topic: str) -> FirstQuestion:
    """목적지와 선택한 주제를 받아 대화를 시작하는 질문 1개를 만든다."""
    response = client.responses.parse(
        model=MODEL,
        input=[
            {
                "role": "system",
                "content": (
                    "당신은 가족의 차량 이동 중 대화를 돕는 진행자입니다. "
                    "선택한 주제로 가족 모두가 답할 첫 질문 1개를 만드세요. "
                    "주제 제목을 그대로 의문형으로 바꾸지 말고 이유, 경험, 기대를 더 끌어내세요. "
                    "예나 아니오로 끝나는 질문과 운전자 지목을 피하고 한 문장으로 작성하세요."
                ),
            },
            {
                "role": "user",
                "content": f"목적지: {destination}\n선택한 주제: {topic}",
            },
        ],
        text_format=FirstQuestion,
    )

    return response.output_parsed


def create_followup_question(topic: str, recent_conversation: str) -> FollowupSuggestion:
    """30초 무음 뒤 최근 대화에 이어지는 질문을 제안할지 판단한다."""
    response = client.responses.parse(
        model=MODEL,
        input=[
            {
                "role": "system",
                "content": (
                    "당신은 가족의 차량 이동 중 대화를 돕는 진행자입니다. "
                    "30초 동안 대화가 없을 때 최근 대화에 자연스럽게 이어지는 질문 1개를 제안하세요. "
                    "최근 대화에 나온 사람, 장소, 행동이나 경험을 구체적으로 이어 가되 "
                    "현재 주제나 앞선 질문을 반복하지 마세요. "
                    "가족 모두가 답할 수 있게 하고 운전자를 지목하지 마세요. "
                    "최근 대화가 없거나, 이야기가 이미 끝났거나, 민감한 갈등이 있거나, "
                    "질문이 억지스러우면 should_suggest는 false, question은 빈 문자열로 반환하세요."
                ),
            },
            {
                "role": "user",
                "content": f"현재 주제: {topic}\n최근 대화:\n{recent_conversation}",
            },
        ],
        text_format=FollowupSuggestion,
    )

    return response.output_parsed


def create_current_story_title(conversation: str) -> CurrentStoryTitle:
    """현재 대화의 핵심을 주행 화면에 표시할 제목 한 줄로 만든다."""
    response = client.responses.parse(
        model=MODEL,
        input=[
            {
                "role": "system",
                "content": (
                    "당신은 차량 안에서 나눈 가족 대화의 현재 주제를 짧게 표시하는 정리자입니다. "
                    "대화에서 지금 중심이 되는 내용을 자연스러운 한국어 제목 한 줄로 만드세요. "
                    "제목은 화면에서 바로 읽을 수 있게 짧게 쓰고 질문형으로 만들지 마세요. "
                    "대화에 없는 내용을 추측하지 말고, 제목을 만들 수 없는 대화라면 빈 문자열을 반환하세요."
                ),
            },
            {
                "role": "user",
                "content": f"현재 가족 대화:\n{conversation}",
            },
        ],
        text_format=CurrentStoryTitle,
    )

    return response.output_parsed


def organize_conversation(conversation: str) -> ConversationSummary:
    """가족 대화를 이야기별로 나누고 제목, 요약, 핵심 대화를 만든다."""
    response = client.responses.parse(
        model=MODEL,
        input=[
            {
                "role": "system",
                "content": (
                    "당신은 차량 안에서 나눈 가족 대화를 기록하는 정리자입니다. "
                    "대화의 중심 주제가 바뀔 때만 이야기를 구분하고, 각 이야기의 짧은 제목과 요약을 만드세요. "
                    "각 이야기에서 화면에 보여 줄 핵심 대화를 0개에서 3개 골라 highlights에 넣으세요. "
                    "각 핵심 대화는 25자 이내의 짧고 자연스러운 말투로 쓰고, 화자 이름과 따옴표는 넣지 마세요. "
                    "길게 말한 내용은 뜻을 유지해 줄이되 대화에 없던 내용을 만들지 말고, "
                    "의미 있는 핵심 대화가 없으면 highlights를 빈 목록으로 반환하세요. "
                    "대화에 없는 내용을 추측하지 말고, 정리할 대화가 없으면 stories를 빈 목록으로 반환하세요."
                ),
            },
            {
                "role": "user",
                "content": f"가족 대화:\n{conversation}",
            },
        ],
        text_format=ConversationSummary,
    )

    return response.output_parsed

