from pydantic import BaseModel, Field


class TopicRecommendations(BaseModel):
    topics: list[str] = Field(
        min_length=2,
        max_length=2,
        description="목적지와 여행 맥락을 반영하고 마침표로 끝나는 가족 대화 제목 2개",
    )


class FirstQuestion(BaseModel):
    question: str = Field(
        description="선택한 주제로 가족 대화를 시작하는 첫 질문",
    )


class FollowupSuggestion(BaseModel):
    should_suggest: bool = Field(
        description="최근 대화에 이어지는 질문을 제안할지 여부",
    )
    question: str = Field(
        description="이어갈 질문. 제안하지 않을 때는 빈 문자열",
    )

