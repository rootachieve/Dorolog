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

- 목적지를 반영한 대화 주제 2개 추천
- 선택한 주제로 첫 질문 생성
- 최근 대화를 반영한 후속 질문 생성 또는 제안 없음 반환
- 대화를 이야기별로 구분
- 이야기 제목, 요약, 참여자 추출
- 가보고 싶은 장소와 말한 사람, 이유 추출
- 여정별 기록 시작
- 음성 인식된 대화 텍스트 누적
- 현재 이야기 미리보기
- 현재까지의 이야기 저장
- 기록 종료 시 남은 대화 자동 저장
- 위 기능을 호출할 수 있는 FastAPI 엔드포인트

## API

| Method | Endpoint | 호출 함수 | 결과 |
| --- | --- | --- | --- |
| GET | `/health` | `health()` | 서버 실행 상태 |
| POST | `/topics` | `recommend_topics()` | 추천 주제 2개 |
| POST | `/questions/first` | `create_first_question()` | 첫 질문 1개 |
| POST | `/questions/follow-up` | `create_followup_question()` | 후속 질문 1개 또는 제안 없음 |
| POST | `/recordings` | `start_recording()` | 기록 ID와 새 여정 기록 |
| POST | `/recordings/{recording_id}/transcripts` | `add_transcript()` | 현재 기록에 추가된 발화 |
| POST | `/recordings/{recording_id}/preview` | `preview_current_story()` | 저장 전 현재 이야기 제목과 요약 |
| POST | `/recordings/{recording_id}/stories` | `save_current_story()` | 저장된 이야기 목록 |
| POST | `/recordings/{recording_id}/stop` | `stop_recording()` | 남은 이야기까지 저장된 최종 기록 |

`preview_current_story()`는 아직 저장하지 않은 `pending_transcript`를 정리해 반환하지만 실제 저장은 하지 않습니다. 앱에서 1분 경과 신호가 왔을 때 호출할 수 있습니다.

`save_current_story()`는 `pending_transcript`를 이야기로 정리해 `saved_stories`에 추가한 뒤, 중복 저장되지 않도록 `pending_transcript`를 비웁니다.

`stop_recording()`은 아직 저장하지 않은 대화가 있으면 먼저 저장하고 기록 상태를 `stopped`로 변경합니다.

각 API의 요청과 응답 JSON은 서버 실행 후 아래 주소에서 확인할 수 있습니다.

```text
http://127.0.0.1:8000/docs
```

## 미구현

- 앱에서 FastAPI를 실제로 호출하는 코드
- Pleos 목적지, 출발, 정차, 도착 신호 연결
- 음성 인식 결과와 화자 정보를 서버에 전달하는 부분
- 30초 무음과 1분 경과를 판단하는 앱 타이머
- 기록 일시정지와 재개
- 남은 목적지까지 기록 중단
- 저장 당시 차량 위치 태그
- 앱 Room DB 또는 서버 DB를 이용한 영구 저장
- 서버 배포와 API 접근 인증

현재 기록은 Python 메모리에 저장되므로 서버를 종료하면 사라집니다.

## 동작 확인 시나리오

아래 시나리오로 주제 추천, 첫 질문, 후속 질문, 이야기 구분, 장소 추출까지 확인했습니다.

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
참여자: 서연, 엄마
가보고 싶은 곳: 광안리 · 서연 · 다 같이 자전거를 타고 싶다고 했다.

2. 맛있었던 호두과자와 할머니 선물
요약: 민준은 아까 천안 휴게소에서 먹은 호두과자가 제일 맛있었다고 했다. 아빠는 할머니께 드리려고 한 상자를 더 샀다고 했다.
참여자: 민준, 아빠

3. 다음 여행에 가보고 싶은 독립기념관
요약: 민준은 다음 여행에는 천안 독립기념관에도 가보고 싶다고 했다.
참여자: 민준
가보고 싶은 곳: 천안 독립기념관 · 민준 · 다음 여행에 방문하고 싶다고 했다.
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
