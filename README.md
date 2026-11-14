# Hyundai-pleos-app-contest

가족의 대화를 잇고, 그 이야기를 지도 위에 남기는 차량용 가족 대화 서비스

Android 앱은 [서버 API 규격](server/API_CONTRACT.md)을 사용합니다. 서버 규격 반영 기준 커밋은 `b311c476bc1e4af399c0120181db746bed96f39a`입니다.

AI 서버의 구현 내용과 실행 방법은 [AI 서버 README](server/README.md), TTS와 API 변경의 통합 검증 범위는 [통합 검증 결과](Android/INTEGRATION_VALIDATION.md)에 정리했습니다.

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
