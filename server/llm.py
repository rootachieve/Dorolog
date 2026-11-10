import os

from dotenv import load_dotenv
from openai import OpenAI

from .schemas import FirstQuestion, FollowupSuggestion, TopicRecommendations


load_dotenv()

client = OpenAI()
MODEL = os.getenv("OPENAI_MODEL", "gpt-6.1-sol")


def recommend_topics(destination: str) -> TopicRecommendations:
    """목적지를 받아 가족이 차 안에서 이야기할 주제 2개를 만든다."""
    response = client.responses.parse(
        model=MODEL,
        input=[
            {
                "role": "system",
                "content": (
                    "당신은 가족의 차량 이동 중 대화를 돕는 진행자입니다. "
                    "목적지를 반영한 서로 다른 가족 대화 주제 2개를 제안하세요. "
                    "첫 주제는 기대나 계획, 둘째 주제는 추억이나 경험을 다루세요. "
                    "질문형이 아닌 짧은 제목으로 작성하고 마침표로 끝내세요. "
                ),
            },
            {
                "role": "user",
                "content": f"목적지: {destination}",
            },
        ],
        text_format=TopicRecommendations,
    )

    return response.output_parsed


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
        reasoning={"effort": "low"},
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

