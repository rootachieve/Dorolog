from datetime import datetime
from typing import Annotated, Optional

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator


class TopicRequest(BaseModel):
    destination_name: Optional[str] = Field(default=None, alias="destinationName")
    previous_conversation_ids: list[str] = Field(
        default_factory=list,
        alias="previousConversationIds",
    )


class FirstQuestionRequest(BaseModel):
    destination: str
    topic: str


class FollowupQuestionRequest(BaseModel):
    topic: str
    recent_conversation: str


class RecordingStartRequest(BaseModel):
    destination: str
    topic: str


class TranscriptRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    text: str
    location: str
    recorded_at: datetime = Field(alias="recordedAt")


class TopicQuestions(BaseModel):
    questions: list[str] = Field(
        min_length=4,
        max_length=4,
        description="목적지 관련 질문 2개와 가족 여행 관련 질문 2개",
    )


class RecommendedTopic(BaseModel):
    id: str
    question: str


class TopicRecommendations(BaseModel):
    topics: list[RecommendedTopic] = Field(
        min_length=4,
        max_length=4,
        description="Android 추천 질문 화면에 전달할 질문 4개",
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


class CurrentStoryTitle(BaseModel):
    title: str = Field(
        description="주행 화면에 표시할 현재 이야기의 짧은 한 줄 제목",
    )


ShortHighlight = Annotated[str, StringConstraints(max_length=25)]
NonEmptyText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]


class Story(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    title: str = Field(
        description="이야기의 핵심을 담은 짧은 한 줄 제목",
    )
    summary: str = Field(
        description="대화에서 확인된 내용만 담은 이야기 요약",
    )
    highlights: list[ShortHighlight] = Field(
        max_length=3,
        description="화면에 표시할 25자 이내 핵심 대화. 최대 3개이며 없으면 빈 목록",
    )
    location: str = Field(
        description="이야기가 시작된 발화에 함께 전달된 지역",
    )
    recorded_at: datetime = Field(
        alias="recordedAt",
        description="이야기가 시작된 발화에 함께 전달된 기록 시각",
    )


class ConversationSummary(BaseModel):
    stories: list[Story] = Field(
        description="주제별로 구분해 정리한 가족 이야기. 대화가 없으면 빈 목록",
    )


class TripStoryUpsertRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    title: NonEmptyText
    region: NonEmptyText
    latitude: Optional[float] = Field(default=None, ge=-90.0, le=90.0)
    longitude: Optional[float] = Field(default=None, ge=-180.0, le=180.0)
    summary: str = ""
    highlights: list[ShortHighlight] = Field(default_factory=list, max_length=3)
    recorded_at: Optional[datetime] = Field(default=None, alias="recordedAt")

    @model_validator(mode="after")
    def validate_coordinate_pair(self):
        if (self.latitude is None) != (self.longitude is None):
            raise ValueError("위도와 경도는 함께 보내야 합니다.")
        return self


class StoredStory(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    title: str
    region: str
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    summary: str
    highlights: list[str]
    recorded_at: Optional[datetime] = Field(default=None, alias="recordedAt")


class TripStoriesResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    stories: list[StoredStory]
    saved_story_ids: list[str] = Field(alias="savedStoryIds")


class SavedStoriesRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    story_ids: list[NonEmptyText] = Field(alias="storyIds")

