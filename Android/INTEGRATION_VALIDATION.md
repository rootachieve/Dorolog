# main 통합 검증

## 통합 기준

- Android API 작업: `ac1fc1f`에서 시작한 `8bf7` 워크트리의 미커밋 변경을 main에 반영했다.
- 서버 계약: `origin/feat/ai-server`의 `b311c476bc1e4af399c0120181db746bed96f39a`를 확인했다. 서버 구현 파일은 main에 가져오거나 수정하지 않았다.
- 새 여행은 생성 응답 ID로 질문을 조회한다. 진행 중인 여행은 같은 ID로 바로 질문을 조회하며, 종료한 여행만 `/resume` 성공 후 질문을 조회한다.
- 최신 계약의 `destinationAddress`와 질문 조회의 현재 내비게이션 `destination` 본문을 반영했다. 목적지 없음은 명시적 `null`, 내비게이션 상태 미확인은 요청 보류로 구분한다.
- 첫 질문과 텍스트 후속 질문은 Pleos TTS로 읽는다. 서버 PCM이 있으면 해당 음성만 재생하며, 재생 완료 후 녹음과 침묵 타이머를 재개한다.

## 검증 결과

| 검사 | 결과 |
| --- | --- |
| 전체 JVM 단위 테스트 | 130개 통과, 실패·오류·건너뜀 0 |
| 앱·계측 APK | debug 빌드 성공 |
| Pleos 화면·음성 계측 | 50개 전체 통과: API 화면 45개 + 실제 Pleos TTS 5개, 실패·건너뜀 0 |
| 실제 서버 코드의 ASGI 계약 검사 | 53개 통과: HTTP 요청 45회, WebSocket 시나리오 4개 |

서버 검사는 위 커밋의 17개 파일을 임시 디렉터리에 그대로 추출하고 새 SQLite DB로 실행했다. 실제 라우트·Pydantic 스키마·대화 프로토콜·기록/저장 로직을 사용했다. 목적지 갱신과 `null` 초기화, 잘못된 좌표 거절, 저장 ID 전체 교체와 빈 선택, 지도 집계, 종료·재개 시 ID 유지, PCM/VAD/응답 순서, 잘못된 WebSocket 입력의 종료 코드를 확인했다.

외부 주제/후속 질문 생성, 이야기 정리, STT 어댑터와 서버 TTS 생성만 테스트 대역으로 교체했다. 실제 OpenAI 호출·TCP/TLS 연결·실차 음질을 검증한 결과는 아니다. Pleos 온디바이스 TTS는 별도의 기기 계측에서 실제 SDK로 검증한다.

## 실행

```sh
cd Android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --max-workers=2
```

기기 검사는 `emulator-5554`에 같은 빌드의 앱과 계측 APK를 설치한 뒤 순차 실행한다. 다른 워크트리에서 같은 패키지를 설치·삭제하거나 계측을 동시에 시작하지 않는다.

```sh
adb -s emulator-5554 shell am instrument --user 10 -w -r -e class com.forever.dorolog.TripSelectionScreenTest,com.forever.dorolog.DorologAppTest,com.forever.dorolog.DepartureScreenTest,com.forever.dorolog.ArchiveScreenTest,com.forever.dorolog.TripSummaryScreenTest,com.forever.dorolog.ParkedScreenTest,com.forever.dorolog.DrivingAudioSessionTest,com.forever.dorolog.DrivingQuestionSpeechTest com.forever.dorolog.test/com.forever.dorolog.DorologTestRunner
```

기기 검사 전 Virtual Console의 DDS 연결·P단·정지 상태와 내비게이션 앱 초기화를 확인했다. 첫 질문 낭독, 텍스트 후속 질문, 서버 PCM 중복 방지, 내비게이션 포커스 양보·녹음 재개, 낭독 중 중단을 실제 Pleos TTS/AudioRecord로 확인했다. WebSocket 입력은 계측용 테스트 대역을 사용했다. SDK 콜백은 실제 시간에 비동기로 전달되므로 화면 검사는 고정 가상 시간 경과 대신 질문/버튼 표시를 기다리며, 실제 Back 검사는 앱 창 포커스를 확인한다.
