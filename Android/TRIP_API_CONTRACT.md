# Dorolog Android–서버 API 규격

Android 앱과 Python 서버를 연결할 때 사용하는 최종 규격이다.

기준 원문은 `origin/feat/ai-server:server/API_CONTRACT.md` (`b311c476bc1e4af399c0120181db746bed96f39a`)이며, 서버 규격과 Android 화면 연결을 함께 변경할 때 이 문서도 갱신한다.

## Android 여행 선택 흐름

- 홈의 시작 버튼은 최근 여행 선택 화면을 연다. `GET /api/trips?limit=6`의 순서를 그대로 표시하며, 종료한 여행도 이어갈 수 있다. 목록 응답의 `status`를 유지해 다음 호출을 결정한다.
- 새 여행은 SDK의 현재 위치와 내비게이션 조회 결과를 확인한 뒤 한 번의 `POST /api/trips`로 생성한다. 목적지가 없으면 사용자가 ‘목적지 없이 시작’을 확인할 때 생성하며, 확인창을 닫으면 생성하지 않는다. 좌표는 유효한 위도·경도 쌍 또는 둘 다 없는 경우만 허용한다.
- 생성 응답의 `id`와 응답에 저장된 목적지를 주행 화면에 전달하고, `POST /api/trips/{tripId}/topics`로 추천 질문을 조회한다. 별도 초안 생성, `requestId`, `/start` 호출은 사용하지 않는다.
- 생성 요청의 응답이 유실되면 서버에 여행이 생성되었을 수 있다. 같은 선택 화면에서는 자동 생성 재시도와 다시 생성을 막고, 최근 목록을 새로 불러와 기존 여행의 이어가기로 복구한다. 서버가 멱등성 키를 지원하지 않아 화면을 나갔다가 다시 생성하면 중복될 수 있다.
- `status`가 `active`인 여행을 선택하면 기존 여행 ID와 저장된 목적지로 바로 주행 화면에 진입해 `POST /api/trips/{tripId}/topics`를 호출한다. `/resume`은 호출하지 않는다.
- `status`가 `finished`인 여행을 선택하면 본문 없는 `POST /api/trips/{tripId}/resume`으로 상태를 변경한다. 응답이 확인된 뒤 같은 여행 ID와 응답에 저장된 목적지로 주행 화면에 진입해 `POST /api/trips/{tripId}/topics`를 호출한다.
- 질문 조회 시 현재 내비게이션 목적지의 이름·주소·좌표를 본문으로 전송한다. 목적지가 없으면 `destination: null`로 서버의 현재 목적지를 비운다. 내비게이션 상태를 확인하지 못한 동안은 기존 목적지를 지우지 않도록 요청을 보류한다.
- 현재 위치·목적지를 확인할 수 없거나 서버 요청·응답 검증이 실패하면 오류를 표시하며, 임의 여행 ID로 기록을 시작하지 않는다.

## 주소 설정

Android 빌드 설정:

```text
-Pdorolog.apiBaseUrl=https://서버주소/api/
-Pdorolog.voiceUrl=wss://서버주소/conversation
```

- REST 기본 주소는 `/api/`로 끝나야 한다.
- 음성 주소는 `/conversation` WebSocket 주소다.
- OpenAI API 키는 Android에 넣지 않고 Python 서버의 `.env`에만 둔다.
- JSON 필드는 REST에서 camelCase, WebSocket 제어 메시지에서 아래 예시와 같은 snake_case를 사용한다.
- 시각은 ISO 8601 문자열을 사용한다.
- 위도와 경도는 둘 다 보내거나 둘 다 `null`로 보낸다.

## 화면별 호출 순서

| 시점 | Android 호출 | 용도 |
| --- | --- | --- |
| 새로 시작 | `POST /api/trips` | 새 여행 ID 발급 |
| 최근 주행 화면 | `GET /api/trips?limit=6` | 최근 여행 카드 표시 |
| 진행 중인 여행 선택 | `POST /api/trips/{tripId}/topics` | 기존 ID로 바로 추천 질문 조회 |
| 종료한 여행 이어가기 | `POST /api/trips/{tripId}/resume` | 기존 여행을 다시 활성화한 뒤 추천 질문 조회 |
| 질문 화면 진입 | `POST /api/trips/{tripId}/topics` | 현재 내비게이션 목적지 갱신 후 추천 질문 4개 조회 |
| 대화 시작 | `WSS /conversation` | PCM 전송, STT·VAD·후속 질문·TTS |
| 정차 화면 진입 | `GET /api/trips/{tripId}/stories` | 남은 대화 정리 후 이야기 후보 조회 |
| 이야기 저장 | `PUT /api/trips/{tripId}/saved-stories` | 남길 이야기 전체 선택 결과 저장 |
| 여행 종료 | `POST /api/trips/{tripId}/finish` | 남은 대화 저장과 기록 종료 |
| 최종 리포트·보관함 상세 | `GET /api/trips/{tripId}` | 여행 정보와 이야기 상세 조회 |
| 이야기 보관함 여행별 탭 | `GET /api/trips?limit=50` | 여행 목록 조회 |
| 이야기 보관함 전국 지도 | `GET /api/stories/map` | 저장 이야기와 지역별 개수 조회 |

## 1. 새 여행 생성

```http
POST /api/trips
```

목적지가 없으면 목적지 필드를 보내지 않는다.

```json
{
  "originName": "서울",
  "originLatitude": 37.5665,
  "originLongitude": 126.978,
  "destinationName": "부산",
  "destinationAddress": "부산광역시 동구",
  "destinationLatitude": 35.1796,
  "destinationLongitude": 129.0756
}
```

응답 `201 Created`:

```json
{
  "id": "trip-id",
  "originName": "서울",
  "originLatitude": 37.5665,
  "originLongitude": 126.978,
  "destinationName": "부산",
  "destinationAddress": "부산광역시 동구",
  "destinationLatitude": 35.1796,
  "destinationLongitude": 129.0756,
  "status": "active",
  "startedAt": "2026-11-14T05:30:00+00:00",
  "endedAt": null,
  "storyCount": 0
}
```

응답의 `id`를 이후 REST의 `{tripId}`와 WebSocket `trip_id`로 계속 사용한다.

## 2. 최근 여행 목록

```http
GET /api/trips?limit=6
```

최근 사용한 순서로 반환한다. `storyCount`는 사용자가 남기기로 확정한 이야기 수다.

```json
{
  "trips": [
    {
      "id": "trip-id",
      "originName": "서울",
      "originLatitude": 37.5665,
      "originLongitude": 126.978,
      "destinationName": "부산",
  "destinationAddress": "부산광역시 동구",
      "destinationLatitude": 35.1796,
      "destinationLongitude": 129.0756,
      "status": "finished",
      "startedAt": "2026-11-14T05:30:00+00:00",
      "endedAt": "2026-11-14T08:10:00+00:00",
      "storyCount": 4
    }
  ]
}
```

## 3. 종료한 여행 이어가기

```http
POST /api/trips/{tripId}/resume
```

요청 본문은 없다. 같은 여행 ID와 기존 이야기를 유지하고 `status`를 `active`로 바꾼 여행 정보를 반환한다.

Android는 최근 목록에서 `status`가 `finished`인 여행을 선택했을 때만 호출한다. `active`인 여행을 선택하면 상태 변경 없이 기존 ID로 4번 추천 질문을 바로 조회한다. 새 여행은 1번 생성 응답의 ID로 4번 추천 질문을 조회한다.

## 4. 추천 질문

```http
POST /api/trips/{tripId}/topics
```

현재 내비게이션 목적지를 보낸다. `name`은 화면에 표시할 장소명이고
`address`는 내비게이션이 제공한 주소 문자열이다.

```json
{
  "destination": {
    "name": "부산역",
    "address": "부산광역시 동구 중앙대로 206",
    "latitude": 35.1151,
    "longitude": 129.0414
  }
}
```

서버는 받은 값으로 `{tripId}`의 현재 목적지를 갱신한 뒤 추천 질문을 만든다.
목적지가 있으면 목적지 관련 질문 2개와 가족 여행 질문 2개를 반환한다.

내비게이션 목적지가 없으면 아래처럼 `null`로 보낸다. 서버는 이전 현재 목적지를 비우고
가족 관련 질문 4개를 반환한다.

```json
{ "destination": null }
```

```json
{
  "topics": [
    { "id": "destination_1", "question": "부산역에 도착하면 가족과 가장 먼저 해보고 싶은 일은 무엇인가요?" },
    { "id": "destination_2", "question": "부산에서 함께 먹고 싶은 음식은 무엇인가요?" },
    { "id": "family_travel_1", "question": "다시 떠올리면 웃음 나는 가족 여행은 언제였나요?" },
    { "id": "family_travel_2", "question": "가족 여행에서 기억에 남는 한 끼는 무엇인가요?" }
  ]
}
```

## 5. 음성 WebSocket

### 연결 시작

Android가 연결 직후 보내는 첫 JSON:

```json
{
  "type": "session.start",
  "protocol_version": 1,
  "session_id": null,
  "topic_id": "destination_1",
  "question": "부산에서 가족과 가장 먼저 해보고 싶은 일은 무엇인가요?",
  "destination_name": "부산",
  "trip_id": "trip-id",
  "current_address": "서울특별시",
  "latitude": 37.5665,
  "longitude": 126.978,
  "audio": {
    "encoding": "pcm_s16le",
    "sample_rate_hz": 24000,
    "channels": 1,
    "frame_duration_ms": 20
  }
}
```

서버 준비 응답:

```json
{ "type": "session.ready", "session_id": "voice-session-id" }
```

이후 Android는 24kHz·모노·16비트 little-endian PCM을 바이너리로 전송한다.

### 위치 변경

```json
{
  "type": "location.changed",
  "trip_id": "trip-id",
  "current_address": "충청남도 천안시",
  "latitude": 36.8151,
  "longitude": 127.1139
}
```

### 30초 무음

```json
{ "type": "response.create", "reason": "silence", "silence_ms": 30000 }
```

서버 응답 순서:

```json
{ "type": "response.started", "response_id": "response-id" }
{ "type": "response.text", "response_id": "response-id", "text": "가족과 다시 해보고 싶은 일이 있나요?" }
```

서버에서 합성한 질문 PCM 바이너리가 있으면 이어서 전송하고, 마지막에 다음 메시지가 온다.

```json
{ "type": "response.done", "response_id": "response-id" }
```

### Android 질문 낭독

선택한 첫 질문은 서버 연결 전에 Pleos TextToSpeech SDK 3.3.1의 온디바이스 모드로 한국어 낭독한다. 완료한 첫 질문은 같은 여행을 일시 정지 후 재개할 때 반복하지 않는다. 서버 주소가 없어도 첫 질문 낭독 후 마이크 입력을 확인할 수 있다.

후속 응답에 PCM이 없으면 `response.done`에서 마지막 `response.text` 전체 질문을 Pleos TTS로 한 번 읽는다. 여러 텍스트 이벤트는 델타가 아닌 최신 전체 질문을 전달한다. PCM이 있으면 기존 `AudioTrack` 재생을 사용해 중복 낭독하지 않는다. `response.started`부터 마이크를 멈추고 실제 재생이 끝난 뒤 녹음과 30초 침묵 타이머를 재개한다.

통화·차량 안전 경고·백그라운드 전환·오디오 포커스 상실은 녹음과 TTS를 함께 중단한다. TTS 초기화·재생 실패나 응답 지연은 오류를 표시하고 일시 정지한다.

발화 감지 메시지:

```json
{ "type": "input.speech_started" }
{ "type": "input.speech_stopped" }
```

후속 질문을 취소할 때:

```json
{ "type": "response.cancel" }
```

## 6. 정차 중 이야기 조회

```http
GET /api/trips/{tripId}/stories
```

30초 전에 정차했다면 서버가 남은 대화를 먼저 정리하므로 응답까지 몇 초 걸릴 수 있다.

```json
{
  "stories": [
    {
      "id": "story-id",
      "title": "광안리에서 타고 싶은 자전거",
      "region": "천안",
      "latitude": 36.8151,
      "longitude": 127.1139,
      "summary": "가족이 광안리에서 함께 자전거를 타고 싶다고 이야기했다.",
      "highlights": ["다 같이 자전거 타고 싶어"],
      "recordedAt": "2026-11-14T14:30:00+09:00"
    }
  ],
  "savedStoryIds": ["story-id"]
}
```

처음 생성된 이야기는 모두 `savedStoryIds`에 포함된다. Android는 전체 선택 상태로 보여주고 사용자가 뺀 결과를 다음 API로 보낸다.

## 7. 남길 이야기 저장

```http
PUT /api/trips/{tripId}/saved-stories
```

선택한 이야기 ID의 전체 목록을 보낸다. 빈 배열은 모두 빼기를 뜻한다.

```json
{ "storyIds": ["story-1", "story-3"] }
```

성공 응답은 `204 No Content`다.

## 8. 여행 종료

```http
POST /api/trips/{tripId}/finish
```

요청 본문은 없다. 남은 대화를 저장하고 기록을 종료한 뒤 `stories`, `savedStoryIds`를 반환한다. 휴게소 정차나 일시정지에는 호출하지 않는다.

## 9. 여행·이야기 상세

```http
GET /api/trips/{tripId}
```

여행 기본 정보에 `stories`, `savedStoryIds`가 추가된 응답을 반환한다. 최종 리포트와 보관함의 여행 상세 화면에서 사용한다.

## 10. 전국 이야기 지도

```http
GET /api/stories/map
```

사용자가 남기기로 확정한 이야기만 반환한다.

```json
{
  "storyCount": 3,
  "tripCount": 2,
  "regions": [
    {
      "region": "서울",
      "storyCount": 2,
      "latitude": 37.5665,
      "longitude": 126.978
    }
  ],
  "stories": [
    {
      "id": "story-id",
      "tripId": "trip-id",
      "title": "서울에서 나눈 가족 이야기",
      "region": "서울",
      "latitude": 37.5665,
      "longitude": 126.978,
      "summary": "가족이 서울에서 함께했던 일을 이야기했다.",
      "highlights": ["그때 정말 재미있었어"],
      "recordedAt": "2026-11-14T14:30:00+09:00"
    }
  ]
}
```

- `storyCount`: 저장 확정된 전체 이야기 수
- `tripCount`: 저장 이야기가 하나 이상 있는 여행 수
- `regions`: 같은 지역명의 이야기 개수와 좌표 평균
- 좌표가 없는 지역은 `latitude`, `longitude`가 `null`이다.

## 오류 처리

| 상태 코드 | 의미 |
| --- | --- |
| `404` | 여행 또는 기록을 찾을 수 없음 |
| `409` | 종료된 기록에 발화를 추가하는 등 현재 상태와 요청이 충돌함 |
| `422` | 필수 필드, 좌표 범위 또는 위도·경도 쌍이 올바르지 않음 |
| `500` | GPT, DB 또는 서버 내부 처리 실패 |

서버 실행 후 `http://127.0.0.1:8000/docs`에서도 REST 요청과 응답 형식을 확인할 수 있다.
