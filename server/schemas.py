from datetime import datetime
from typing import Annotated, Literal, Optional

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
    model_config = ConfigDict(populate_by_name=True)

    destination: str
    topic: str
    trip_id: str = Field(default="", alias="tripId")


class TranscriptRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    text: str
    location: str
    recorded_at: datetime = Field(alias="recordedAt")
    latitude: Optional[float] = Field(default=None, ge=-90.0, le=90.0)
    longitude: Optional[float] = Field(default=None, ge=-180.0, le=180.0)

    @model_validator(mode="after")
    def validate_coordinate_pair(self):
        if (self.latitude is None) != (self.longitude is None):
            raise ValueError("위도와 경도는 함께 보내야 합니다.")
        return self


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
SessionText = Annotated[
    str,
    StringConstraints(strip_whitespace=True, min_length=1, max_length=256),
]


class ConversationAudioFormat(BaseModel):
    encoding: Literal["pcm_s16le"]
    sample_rate_hz: Literal[24000]
    channels: Literal[1]
    frame_duration_ms: Literal[20]


class ConversationStartMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: Literal["session.start"]
    protocol_version: Literal[1]
    session_id: Optional[SessionText] = None
    topic_id: SessionText
    question: str = Field(min_length=1, max_length=4000)
    destination_name: Optional[str] = Field(default=None, max_length=1000)
    trip_id: SessionText
    current_address: Optional[str] = Field(default=None, max_length=1000)
    latitude: Optional[float] = Field(default=None, ge=-90.0, le=90.0)
    longitude: Optional[float] = Field(default=None, ge=-180.0, le=180.0)
    audio: ConversationAudioFormat

    @model_validator(mode="after")
    def validate_coordinate_pair(self):
        if (self.latitude is None) != (self.longitude is None):
            raise ValueError("위도와 경도는 함께 보내야 합니다.")
        return self


class ConversationLocationMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: Literal["location.changed"]
    trip_id: SessionText
    current_address: Optional[str] = Field(default=None, max_length=1000)
    latitude: Optional[float] = Field(default=None, ge=-90.0, le=90.0)
    longitude: Optional[float] = Field(default=None, ge=-180.0, le=180.0)

    @model_validator(mode="after")
    def validate_coordinate_pair(self):
        if (self.latitude is None) != (self.longitude is None):
            raise ValueError("위도와 경도는 함께 보내야 합니다.")
        return self


class ConversationResponseCreateMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: Literal["response.create"]
    reason: Literal["silence"]
    silence_ms: Literal[30000]


class ConversationResponseCancelMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: Literal["response.cancel"]


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


class TripCreateRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    origin_name: NonEmptyText = Field(
        default="위치 정보 없음",
        alias="originName",
    )
    origin_latitude: Optional[float] = Field(
        default=None,
        alias="originLatitude",
        ge=-90.0,
        le=90.0,
    )
    origin_longitude: Optional[float] = Field(
        default=None,
        alias="originLongitude",
        ge=-180.0,
        le=180.0,
    )
    destination_name: Optional[NonEmptyText] = Field(
        default=None,
        alias="destinationName",
    )
    destination_latitude: Optional[float] = Field(
        default=None,
        alias="destinationLatitude",
        ge=-90.0,
        le=90.0,
    )
    destination_longitude: Optional[float] = Field(
        default=None,
        alias="destinationLongitude",
        ge=-180.0,
        le=180.0,
    )

    @model_validator(mode="after")
    def validate_coordinate_pairs(self):
        if (self.origin_latitude is None) != (self.origin_longitude is None):
            raise ValueError("출발지 위도와 경도는 함께 보내야 합니다.")
        if (self.destination_latitude is None) != (self.destination_longitude is None):
            raise ValueError("목적지 위도와 경도는 함께 보내야 합니다.")
        return self


class TripResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    origin_name: str = Field(alias="originName")
    origin_latitude: Optional[float] = Field(alias="originLatitude")
    origin_longitude: Optional[float] = Field(alias="originLongitude")
    destination_name: Optional[str] = Field(alias="destinationName")
    destination_latitude: Optional[float] = Field(alias="destinationLatitude")
    destination_longitude: Optional[float] = Field(alias="destinationLongitude")
    status: Literal["active", "finished"]
    started_at: datetime = Field(alias="startedAt")
    ended_at: Optional[datetime] = Field(alias="endedAt")
    story_count: int = Field(alias="storyCount", ge=0)


class RecentTripsResponse(BaseModel):
    trips: list[TripResponse]


class TripDetailResponse(TripResponse):
    stories: list[StoredStory]
    saved_story_ids: list[str] = Field(alias="savedStoryIds")


class StoryMapItem(StoredStory):
    trip_id: str = Field(alias="tripId")


class StoryMapRegion(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    region: str
    story_count: int = Field(alias="storyCount", ge=1)
    latitude: Optional[float] = None
    longitude: Optional[float] = None


class StoryMapResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    story_count: int = Field(alias="storyCount", ge=0)
    trip_count: int = Field(alias="tripCount", ge=0)
    regions: list[StoryMapRegion]
    stories: list[StoryMapItem]

