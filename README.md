# Hyundai-pleos-app-contest

가족의 대화를 잇고, 그 이야기를 지도 위에 남기는 차량용 가족 대화 서비스

## AI 서버 구현 방식

AI 기능은 Python 함수로 구현했고, 앱에서는 FastAPI를 통해 JSON으로 요청하고 결과를 받을 수 있게 구성했습니다.

```text
앱의 JSON 요청
→ FastAPI 엔드포인트
→ Python 함수 실행
→ 필요한 경우 GPT API 호출
→ JSON 결과 반환
```

## 서버 폴더 구조

```text
server/
├── api.py                 # FastAPI 엔드포인트
├── schemas.py             # 요청·응답 형식
├── ai/llm.py              # 모든 GPT 기능
├── voice/                 # WebSocket·VAD·STT·TTS
├── recording/             # 대화 기록·SQLite
└── demos/                 # 기능 확인용 실행 코드
```

## 구현 완료

- 목적지 관련 질문 2개와 가족 여행 질문 2개 추천 (`recommend_topics()`)
- 선택한 주제로 첫 질문 생성 (`create_first_question()`)
- 최근 대화를 반영한 후속 질문 생성 또는 제안 없음 반환 (`create_followup_question()`)
- 대화를 이야기별로 구분 (`organize_conversation()`)
- 이야기 제목과 요약 생성 (`organize_conversation()`)
- 여정별 기록 시작 (`start_recording()`)
- 음성 인식된 대화와 기록 시각·지역·좌표 누적 (`add_transcript()`)
- 주행 중 현재 이야기 제목 미리보기 (`preview_current_story()`)
- 정차 중 제목, 요약, 핵심 대화가 포함된 이야기 후보 생성 (`get_story_candidates()`)
- 대화 내용은 유지하고 남은 구간 또는 여정 기록 종료 (`stop_recording()`)
- SQLite에 여행별 이야기와 저장 선택 보관 (`server/recording/database.py`)
- SQLite에 출발지·목적지·진행 상태를 포함한 여행 정보 보관 (`server/recording/database.py`)
- 새 여행 생성·최근 주행·이어가기·여행 상세 API (`server/api.py`)
- 전국 지도용 저장 이야기·지역별 개수 조회 API (`get_story_map()`)
- Android 정차 화면용 이야기 등록·조회·선택 저장 API (`server/api.py`)
- Android 음성 연결의 세션 시작·PCM 수신·위치 갱신 (`run_conversation()`)
- OpenAI Realtime VAD·STT 연결과 인식 텍스트 기록 (`receive_stt_events()`)
- 30초 무음 요청 시 최근 대화를 반영한 후속 질문 반환 (`create_followup_response()`)
- 후속 질문을 24kHz PCM 음성으로 변환해 Android에 전송 (`stream_speech_pcm()`)
- 누적 대화를 이야기로 자동 정리해 SQLite에 저장 (`save_pending_stories()`)
- 위 기능을 호출할 수 있는 FastAPI 엔드포인트 (`server/api.py`)

## API

Android 연결용 전체 요청·응답 규격은 [`server/API_CONTRACT.md`](server/API_CONTRACT.md)에 정리되어 있습니다.

| Method | Endpoint | 호출 함수 | 결과 |
| --- | --- | --- | --- |
| GET | `/health` | `health()` | 서버 실행 상태 |
| POST | `/api/trips` | `create_trip()` | 새 여행 생성과 여행 ID 발급 |
| GET | `/api/trips?limit=6` | `list_recent_trips()` | 최근 사용한 여행 목록 |
| POST | `/api/trips/{trip_id}/topics` | `update_trip_destination()`, `recommend_topics()` | 현재 목적지 갱신 후 추천 질문 4개 |
| GET | `/api/stories/map` | `get_story_map()` | 전국 지도의 이야기·여행 수와 지역별 이야기 조회 |
| GET | `/api/trips/{trip_id}` | `get_trip()`, `list_stories()` | 여행 정보와 전체 이야기 조회 |
| POST | `/api/trips/{trip_id}/resume` | `resume_trip()` | 기존 여행 이어가기 |
| POST | `/questions/first` | `create_first_question()` | 첫 질문 1개 |
| POST | `/questions/follow-up` | `create_followup_question()` | 후속 질문 1개 또는 제안 없음 |
| POST | `/recordings` | `start_recording()` | 기록 ID와 새 여정 기록 |
| POST | `/recordings/{recording_id}/transcripts` | `add_transcript()` | 현재 기록에 추가된 발화·지역·시각 |
| POST | `/recordings/{recording_id}/preview` | `preview_current_story()` | 주행 화면에 표시할 현재 이야기 제목 |
| POST | `/recordings/{recording_id}/stories` | `get_story_candidates()` | 저장 전 이야기 후보 목록 |
| POST | `/recordings/{recording_id}/stop` | `stop_recording()` | 종료 상태와 종료 시간이 반영된 기록 |
| PUT | `/api/trips/{trip_id}/stories/{story_id}` | `upsert_story()` | AI가 정리한 이야기 등록 또는 수정 |
| GET | `/api/trips/{trip_id}/stories` | `save_trip_pending_stories()`, `list_stories()` | 남은 대화 강제 정리 후 여행 이야기와 저장된 이야기 ID 조회 |
| POST | `/api/trips/{trip_id}/finish` | `finish_trip_recordings()` | 남은 대화 저장, 기록 종료 후 전체 이야기 반환 |
| PUT | `/api/trips/{trip_id}/saved-stories` | `replace_saved_story_ids()` | 남길 이야기 ID 전체 교체 |
| WS | `/conversation` | `run_conversation()` | PCM의 말 시작·종료 감지, STT 기록, 30초 무음 후속 질문과 TTS 반환 |

`preview_current_story()`는 아직 저장하지 않은 `pending_transcript`로 현재 이야기 제목만 만들어 반환합니다. 기록을 저장하거나 비우지 않으며, 앱에서 1분 경과 신호가 왔을 때 호출할 수 있습니다.

대화 입력에는 `text`, `location`, `recordedAt`, `latitude`, `longitude`를 함께 보냅니다. `recordedAt`은 `2026-11-14T14:30:00+09:00`과 같은 ISO 8601 형식입니다.

```json
{
  "text": "예전에 여기서 호두과자 먹었잖아",
  "location": "천안",
  "recordedAt": "2026-11-14T14:30:00+09:00",
  "latitude": 36.8151,
  "longitude": 127.1139
}
```

30초 무음 요청이 오면 아직 정리하지 않은 대화를 `organize_conversation()`으로 미리 정리합니다. Android가 정차 화면에서 이야기 목록을 조회할 때는 30초를 채우지 못한 짧은 대화도 먼저 강제로 정리하고, WebSocket 연결 종료 시에도 남은 대화를 정리합니다. 각 이야기에는 안정적인 ID를 발급하고 시작 발화의 지역·좌표·시각을 붙여 SQLite에 저장합니다.

강제 정리는 발화 수가 적다는 이유만으로 내용을 부풀리지 않습니다. 구체적인 사건·경험·계획·감정이 없고 인사, 감탄, 짧은 맞장구나 음성 인식 오류뿐이면 이야기 후보를 만들지 않습니다.

새로 생성된 이야기는 정차 화면에서 모두 선택된 상태로 보이도록 `savedStoryIds`에도 추가합니다. 이후 사용자가 이야기를 빼거나 다시 선택해 저장하면 `PUT /api/trips/{trip_id}/saved-stories`가 그 선택 전체로 교체합니다.

`get_story_candidates()`는 정차 중이나 기록 종료 후 `pending_transcript`를 이야기별 제목, 요약, 핵심 대화, 시작 지역과 시각으로 정리합니다. 핵심 대화는 이야기마다 최대 3개이며 각 25자 이내입니다. 결과를 반환할 뿐 기록을 저장하거나 `pending_transcript`를 비우지는 않습니다.

`stop_recording()`은 대화를 정리하거나 저장하지 않고 기록 상태를 `stopped`로 변경하고 종료 시간을 남깁니다. 기존 `pending_transcript`는 그대로 유지됩니다.

실제 여행을 종료할 때는 `POST /api/trips/{trip_id}/finish`를 호출합니다. 서버는 아직 정리하지 않은 대화를 먼저 SQLite에 저장하고 해당 여행의 모든 기록을 `stopped`로 바꾼 뒤, 여행의 전체 이야기와 `savedStoryIds`를 반환합니다. 휴게소 정차나 일시정지 때는 이 API를 호출하지 않습니다.

새 여행은 `POST /api/trips`로 만들며 서버가 `trip_id`를 발급합니다. 출발지와 목적지 이름, 위도·경도는 선택적으로 함께 보낼 수 있고 위도와 경도는 반드시 한 쌍으로 보냅니다. 목적지가 없는 주행은 목적지 필드를 보내지 않습니다.

`GET /api/trips?limit=6`은 최근 사용한 순서대로 여행을 반환합니다. `storyCount`는 사용자가 남기기로 확정한 이야기 수입니다. 기존 여행의 `POST /api/trips/{trip_id}/resume`을 호출하면 같은 여행 ID를 유지한 채 상태가 다시 `active`가 되므로 이후 이야기가 기존 여행에 이어서 저장됩니다.

각 API의 요청과 응답 JSON은 서버 실행 후 아래 주소에서 확인할 수 있습니다.

```text
http://127.0.0.1:8000/docs
```

## 미구현

- 앱에서 FastAPI를 실제로 호출하는 코드
- Pleos 목적지, 출발, 정차, 도착 신호 연결
- 앱에서 음성 인식 일시정지와 재개
- 1분 경과를 판단하는 앱 타이머
- 서버 배포와 API 접근 인증

이동 중 원본 대화는 Python 메모리에 있어 서버를 종료하면 사라집니다. AI가 정리한 이야기와 사용자의 저장 선택은 `data/dorolog.db`에 보관합니다. Android 정차 화면은 여행 이야기 조회와 저장 선택 API를 통해 이 데이터를 사용합니다.

## 동작 확인 시나리오

아래 시나리오로 주제 추천, 첫 질문, 후속 질문, 이야기 구분까지 확인했습니다.

```text
목적지: 부산할머니댁

추천 주제 (4.72초)
1. 부산 할머니댁에서 함께하고 싶은 일.
2. 부산 할머니댁에서의 따뜻한 추억.

주제 선택 (1 또는 2): 1

첫 질문 (5.48초)
이번에 부산 할머니댁에 가면 할머니와 가장 먼저 함께하고 싶은 일은 무엇이고, 그 일이 왜 기대되나요?

최근 대화: 서연: 부산에 도착하면 광안리에서 다 같이 자전거 타고 싶어. 엄마: 예전에 제주도에서 자전거 탔을 때 너 넘어졌던 거 기억나? 서연: 이번에는 안 넘어질 자신 있어. 민준: 나는 아까 천안 휴게소에서 먹은 호두과자가 제일 맛있었어. 아빠: 할머니 드리려고 한 상자 더 샀지. 민준: 다음 여행에는 천안 독립기념관도 가보고 싶어.

이어갈 질문 (9.78초)
민준이가 말한 독립기념관에 다 같이 간다면, 우리나라 역사에서 어떤 이야기를 더 알아보고 싶어?

대화 정리 (8.38초)

1. 광안리에서 타고 싶은 자전거
요약: 서연은 부산에 도착하면 광안리에서 다 같이 자전거를 타고 싶다고 했다. 엄마가 예전에 제주도에서 자전거를 타다가 넘어진 일을 꺼내자, 서연은 이번에는 넘어지지 않을 자신이 있다고 했다.

2. 맛있었던 호두과자와 할머니 선물
요약: 민준은 아까 천안 휴게소에서 먹은 호두과자가 제일 맛있었다고 했다. 아빠는 할머니께 드리려고 한 상자를 더 샀다고 했다.

3. 다음 여행에 가보고 싶은 독립기념관
요약: 민준은 다음 여행에는 천안 독립기념관에도 가보고 싶다고 했다.
```

## 실행 방법

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r server/requirements.txt
cp .env.example .env
```

`.env`의 `OPENAI_API_KEY`에 발급받은 API 키를 입력합니다.

AI 기능 데모:

```bash
python -m server.demos.ai_demo
```

기록 관리 데모:

```bash
python -m server.demos.recording_demo
```

FastAPI 서버:

```bash
uvicorn server.api:app --reload
```
Android 앱은 [`feat/ai-server`의 API_CONTRACT.md](https://github.com/rootachieve/Dorolog/blob/feat/ai-server/server/API_CONTRACT.md)를 사용합니다. 반영 기준 커밋은 `b311c476bc1e4af399c0120181db746bed96f39a`입니다.

TTS와 API 변경의 통합 검증 범위는 [통합 검증 결과](Android/INTEGRATION_VALIDATION.md)에 정리했습니다.

서버 주소는 빌드할 때 지정합니다.

```sh
cd Android
./gradlew assembleDebug \
  -Pdorolog.apiBaseUrl=https://서버주소/api/ \
  -Pdorolog.voiceUrl=wss://서버주소/conversation
```

REST 주소는 `/api/`로 끝나야 합니다. OpenAI 키는 Python 서버의 환경 변수로만 설정합니다.
최근 여행은 `GET trips?limit=6`, 보관함은 `GET trips?limit=50`, 전국 지도는 `GET stories/map`을 사용합니다. 정렬은 앱에서 수행합니다.
새 여행은 현재 출발지와 목적지를 함께 전송해 생성합니다. 미종료 여행은 기존 ID로 바로 질문을 조회하고, `finished` 여행만 본문 없는 `POST trips/{id}/resume` 성공 후 질문을 조회합니다. 추천 질문 요청에는 현재 내비게이션 목적지의 이름·주소·좌표를 보내며, 목적지가 없으면 `destination: null`로 서버의 현재 목적지를 비웁니다.

서버가 새 여행 생성 요청을 처리한 뒤 응답이 끊겼을 때 중복 생성을 막으려면 최근 여행 목록을 새로고침하고 해당 여행을 이어갑니다. 이 계약에는 생성 요청의 중복 방지 키가 없습니다. 현재 목적지는 추천 질문 요청에서 갱신합니다.

