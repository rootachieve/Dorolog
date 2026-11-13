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

## 구현 완료

- 목적지 관련 질문 2개와 가족 여행 질문 2개 추천 (`recommend_topics()`)
- 선택한 주제로 첫 질문 생성 (`create_first_question()`)
- 최근 대화를 반영한 후속 질문 생성 또는 제안 없음 반환 (`create_followup_question()`)
- 대화를 이야기별로 구분 (`organize_conversation()`)
- 이야기 제목과 요약 생성 (`organize_conversation()`)
- 여정별 기록 시작 (`start_recording()`)
- 음성 인식된 대화와 기록 시각·지역 누적 (`add_transcript()`)
- 주행 중 현재 이야기 제목 미리보기 (`preview_current_story()`)
- 정차 중 제목, 요약, 핵심 대화가 포함된 이야기 후보 생성 (`get_story_candidates()`)
- 대화 내용은 유지하고 남은 구간 또는 여정 기록 종료 (`stop_recording()`)
- SQLite에 여행별 이야기와 저장 선택 보관 (`server/database.py`)
- Android 정차 화면용 이야기 등록·조회·선택 저장 API (`server/api.py`)
- 위 기능을 호출할 수 있는 FastAPI 엔드포인트 (`server/api.py`)

## API

| Method | Endpoint | 호출 함수 | 결과 |
| --- | --- | --- | --- |
| GET | `/health` | `health()` | 서버 실행 상태 |
| POST | `/topics` | `recommend_topics()` | 추천 질문 4개 |
| POST | `/questions/first` | `create_first_question()` | 첫 질문 1개 |
| POST | `/questions/follow-up` | `create_followup_question()` | 후속 질문 1개 또는 제안 없음 |
| POST | `/recordings` | `start_recording()` | 기록 ID와 새 여정 기록 |
| POST | `/recordings/{recording_id}/transcripts` | `add_transcript()` | 현재 기록에 추가된 발화·지역·시각 |
| POST | `/recordings/{recording_id}/preview` | `preview_current_story()` | 주행 화면에 표시할 현재 이야기 제목 |
| POST | `/recordings/{recording_id}/stories` | `get_story_candidates()` | 저장 전 이야기 후보 목록 |
| POST | `/recordings/{recording_id}/stop` | `stop_recording()` | 종료 상태와 종료 시간이 반영된 기록 |
| PUT | `/api/trips/{trip_id}/stories/{story_id}` | `upsert_story()` | AI가 정리한 이야기 등록 또는 수정 |
| GET | `/api/trips/{trip_id}/stories` | `list_stories()` | 여행 이야기와 저장된 이야기 ID 조회 |
| PUT | `/api/trips/{trip_id}/saved-stories` | `replace_saved_story_ids()` | 남길 이야기 ID 전체 교체 |

`preview_current_story()`는 아직 저장하지 않은 `pending_transcript`로 현재 이야기 제목만 만들어 반환합니다. 기록을 저장하거나 비우지 않으며, 앱에서 1분 경과 신호가 왔을 때 호출할 수 있습니다.

대화 입력에는 `text`, `location`, `recordedAt`을 함께 보냅니다. `recordedAt`은 `2026-11-14T14:30:00+09:00`과 같은 ISO 8601 형식입니다.

```json
{
  "text": "예전에 여기서 호두과자 먹었잖아",
  "location": "천안",
  "recordedAt": "2026-11-14T14:30:00+09:00"
}
```

`get_story_candidates()`는 정차 중이나 기록 종료 후 `pending_transcript`를 이야기별 제목, 요약, 핵심 대화, 시작 지역과 시각으로 정리합니다. 핵심 대화는 이야기마다 최대 3개이며 각 25자 이내입니다. 결과를 반환할 뿐 기록을 저장하거나 `pending_transcript`를 비우지는 않습니다.

`stop_recording()`은 대화를 정리하거나 저장하지 않고 기록 상태를 `stopped`로 변경하고 종료 시간을 남깁니다. 기존 `pending_transcript`는 그대로 유지됩니다.

각 API의 요청과 응답 JSON은 서버 실행 후 아래 주소에서 확인할 수 있습니다.

```text
http://127.0.0.1:8000/docs
```

## 미구현

- 앱에서 FastAPI를 실제로 호출하는 코드
- Pleos 목적지, 출발, 정차, 도착 신호 연결
- 음성 인식 결과를 서버에 전달하는 부분
- 앱에서 음성 인식 일시정지와 재개
- 30초 무음과 1분 경과를 판단하는 앱 타이머
- 앱 Room DB 또는 서버 DB를 이용한 영구 저장
- 서버 배포와 API 접근 인증

이동 중 대화 기록은 Python 메모리에 있어 서버를 종료하면 사라집니다. 정리된 이야기와 사용자의 저장 선택은 `data/dorolog.db`에 보관합니다. Android 정차 화면은 여행 이야기 조회와 저장 선택 API를 통해 이 데이터를 사용합니다.

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
python -m server.demo
```

기록 관리 데모:

```bash
python -m server.recording_demo
```

FastAPI 서버:

```bash
uvicorn server.api:app --reload
```
