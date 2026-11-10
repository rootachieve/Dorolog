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


class WantedPlace(BaseModel):
    name: str = Field(
        description="가보고 싶다고 말한 장소 이름",
    )
    speaker: str = Field(
        description="해당 장소에 가보고 싶다고 말한 사람",
    )
    reason: str = Field(
        description="그 장소에서 하고 싶다고 말한 일을 한 문장으로 정리한 내용",
    )


class Story(BaseModel):
    title: str = Field(
        description="이야기의 핵심을 담은 짧은 한 줄 제목",
    )
    summary: str = Field(
        description="대화에서 확인된 내용만 담은 이야기 요약",
    )
    speakers: list[str] = Field(
        description="이야기에 참여한 사람 이름",
    )
    wanted_places: list[WantedPlace] = Field(
        description="이야기에서 가보고 싶다고 말한 장소. 없으면 빈 목록",
    )


class ConversationSummary(BaseModel):
    stories: list[Story] = Field(
        description="주제별로 구분해 정리한 가족 이야기. 대화가 없으면 빈 목록",
    )

