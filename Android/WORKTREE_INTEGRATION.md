# Android 화면과 서버 연결

서버 계약은 [`feat/ai-server/server/API_CONTRACT.md`](https://github.com/rootachieve/Dorolog/blob/feat/ai-server/server/API_CONTRACT.md)를 따른다. 이번 반영 기준은 `b311c476bc1e4af399c0120181db746bed96f39a`이다. 서버 구현·배포는 이 Android 변경에 포함하지 않는다.

## 화면 이동

- 홈 → 최근 여행 선택. `GET trips?limit=6`의 순서를 유지한다.
- 새 여행은 현재 출발지와 선택한 내비게이션 목적지를 `POST trips`로 전송한다. 목적지가 없으면 확인 후 목적지 필드를 생략한다.
- 미종료 여행을 선택하면 기존 `id`와 저장된 목적지를 주제 선택 화면으로 전달해 바로 ④ 질문을 조회한다. `finished` 여행만 본문 없는 ③ `POST trips/{id}/resume` 성공 후 ④ 질문을 조회한다. 새 여행은 ① 생성 응답의 `id`와 목적지로 ④ 질문을 조회한다.
- `POST trips/{id}/topics`에 현재 내비게이션 목적지의 `name`, `address`, `latitude`, `longitude`를 `destination` 본문으로 보낸다. 목적지가 없으면 `destination: null`로 이전 목적지를 비운다. 내비게이션 상태 미확인 시 요청을 보류한다.
- 주행 대화는 `/conversation` WebSocket에서 24kHz·모노·16비트 little-endian PCM과 계약의 제어 메시지를 사용한다. REST와 음성에 같은 여행 ID를 전달한다. 선택한 첫 질문은 Pleos TTS로 읽는다. 후속 응답은 서버 PCM이 있으면 이를 재생하고, 텍스트만 있으면 Pleos TTS로 읽어 중복 낭독을 막는다. 실제 재생 완료 후 녹음을 재개한다.
- 도착과 P단 확인 → `GET trips/{id}/stories` → 전체 후보와 `savedStoryIds`로 주차 이야기 선택을 표시한다. 저장은 선택한 ID 전체를 `PUT trips/{id}/saved-stories`로 보낸다. 빈 배열도 유효하다.
- 여행 종료는 녹음·재생 중단 → 본문 없는 `POST trips/{id}/finish` → 성공 응답의 `stories`, `savedStoryIds` 확인 → `GET trips/{id}`로 결산을 조회한다. 일시정지와 휴게소 정차에서는 종료를 호출하지 않는다.
- 보관함 여행 목록은 `GET trips?limit=50`, 전국 지도는 `GET stories/map`을 사용한다. 지도는 저장된 이야기 지역·좌표·개수로 표시하며 여행 목적지로 집계하지 않는다. 서버가 제공하지 않는 정렬 쿼리는 보내지 않고 앱에서 정렬한다.
- 지도 이야기와 여행 카드 모두 해당 여행 ID로 결산을 연다. 보관함의 과거 여행을 조회할 때 종료 API를 호출하지 않는다. 뒤로 가면 보관함 탭·정렬·지역 선택을 유지한다.

## 응답 처리

결산은 상세 응답의 `id`가 요청한 여행 ID와 같은지 확인한다. `savedStoryIds`에 포함된 이야기만 표시하며, `summary`를 본문으로, `highlights`를 핵심 대화로, `recordedAt`을 기록 시각으로 사용한다. 기록 시각이나 좌표가 없는 경우 가상의 값으로 채우지 않는다. 위도·경도는 유효한 쌍 또는 둘 다 없는 값만 허용한다.

서버 오류·잘못된 응답은 오류와 재시도로 표시한다. 새 여행 생성 응답이 유실되면 중복 생성을 피하기 위해 해당 화면에서 자동 재전송하지 않고 최근 목록 새로고침 후 이어가기로 복구한다. 현재 계약은 생성 중복 방지 키를 지원하지 않는다.

## 차량과 설정

목록·지도·상세 대화 탐색은 `CarUxRestrictionsManager`가 허용할 때만 제공한다. 차량 상태 미확인 시 제한하고, 주차 이야기 선택은 도착·P단·정지 상태를 확인한다. 오디오 포커스와 포그라운드 조건을 유지한다.

빌드 주소는 `-Pdorolog.apiBaseUrl=https://host/api/`, `-Pdorolog.voiceUrl=wss://host/conversation`으로 설정한다. HTTPS/WSS를 사용하며 OpenAI 키는 Android에 넣지 않는다. 지도 키는 기존 `dorolog.kakaoMapNativeAppKey` 설정을 사용한다. 실제 서버 주소가 없으면 원격 서버 검증을 완료한 것으로 취급하지 않는다.

검증은 JVM 계약·상태 테스트, 앱/계측 APK 빌드, Pleos 계측 테스트와 Virtual Console 차량 이벤트로 수행한다. 실제 AI·STT·TTS 서버와 실차 검증 여부는 작업 결과에서 별도로 보고한다.
