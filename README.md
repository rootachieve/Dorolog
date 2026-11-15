# 도로록 (Dorolog)

> 도로록(Doro+Log)은 함께 달리는 도로 위에서 스쳐갈 가족의 대화를 AI로 이어주고, 그날의 장소와 마음을 오래 간직할 추억의 기록으로 남기는 차량용 가족 소통 서비스입니다.

도로록은 차량의 이동 맥락에 맞춰 AI가 가족의 대화를 자연스럽게 이어주고, 차 안에서 나눈 이야기를 장소와 연결해 추억으로 기록하는 AI 기반 차량용 가족 소통 서비스입니다.

## 주요 기능

- 목적지 관련 질문과 가족 여행 질문 추천
- 무음이 이어질 때 최근 대화에 맞는 후속 질문 제안
- 가족 대화 음성 인식과 이야기 제목·요약·핵심 대화 생성
- 정차 중 남길 이야기 선택
- 목적지 도착 후 이동 순서에 따른 이야기 리포트
- 여정별 보관함과 지역별 이야기 지도

## 실행 방법

### 1. APK 설치

Pleos Connect Emulator를 실행한 뒤 저장소의 release APK를 설치합니다.

```bash
adb install -r apk/Doblox_Dorolog_release.apk
```

설치 후 에뮬레이터 앱 목록에서 **도로록**을 실행하고 마이크·통화 상태 확인 권한을 허용합니다.

### 2. 앱 사용

1. 홈 화면에서 `시작하기`를 선택합니다.
2. `새로 시작` 또는 최근 여정의 `이어가기`를 선택합니다.
3. 내비게이션 목적지가 있으면 목적지 기반 질문을, 목적지가 없으면 가족 관련 질문을 확인합니다.
4. 질문을 선택하고 주행을 시작하면 가족 대화가 자동으로 기록됩니다.
5. 차량을 정차하면 남길 이야기를 고르고 저장할 수 있습니다.
6. 목적지에 도착하면 이야기 리포트와 보관함을 확인할 수 있습니다.

### 3. Virtual Console 연결

차량 속도·기어·정차·안전 이벤트를 테스트하려면 포트를 연결한 뒤 브라우저에서 Virtual Console을 엽니다.

```bash
adb forward tcp:8088 tcp:8088
```

```text
http://127.0.0.1:8088/
```

## 테스트 환경 및 필요한 설정

| 항목 | 설정 |
| --- | --- |
| 앱 버전 | 1.0.1 |
| 패키지 | `com.forever.dorolog` |
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 이상 |
| 기본 화면 규격 | 850 × 592 |
| 추가 화면 검증 | 360 × 640, 640 × 360, 글꼴 크기 2배 |
| Android SDK | compileSdk·targetSdk 36, minSdk 28 |
| 서버 연결 | HTTPS REST API, WSS 음성 WebSocket |

- AI 기능을 사용하려면 프로젝트 루트의 `.env` 파일에 `OPENAI_API_KEY={Your key}`를 설정해야 합니다.
- Android는 `Android/local.properties` 파일 하단에 `dorolog.kakaoMapNativeAppKey={Your Kakao map key}`를 추가해야 합니다.
- AI 기능과 이야기 저장을 사용하려면 인터넷 연결이 필요합니다.
- 기본 배포 서버 주소는 APK에 포함되어 있어 별도의 서버 설정 없이 실행할 수 있습니다.
- 내비게이션 목적지가 없어도 `목적지 없이 시작`을 선택해 사용할 수 있습니다.
- 차량 상태 테스트에는 Pleos Connect Virtual Console을 사용합니다.

## 사용한 Pleos Connect SDK

| SDK | 버전 | 용도 |
| --- | --- | --- |
| NaviHelper | 3.0.5 | 내비게이션 목적지·도착 이벤트 연동 |
| Vehicle | 2.3.2 | 속도·기어·정차·안전 상태 연동 |
| TextToSpeech | 3.3.1 | 첫 질문과 후속 질문 음성 안내 |

## APK 파일

- [apk/Doblox_Dorolog_release.apk](apk/Doblox_Dorolog_release.apk)
- 팀명: Doblox
- 파일명: `Doblox_Dorolog_release.apk`

## 개발 문서

- [Android–서버 API 규격](server/API_CONTRACT.md)
- [AI 서버 실행 및 배포](server/README.md)
- [통합 검증 결과](Android/INTEGRATION_VALIDATION.md)
