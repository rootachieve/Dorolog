# Dorolog AI Server

Dorolog Android 앱에서 사용하는 FastAPI 서버입니다. OpenAI API를 이용해 추천 질문, 음성 인식·합성, 대화 정리를 처리하고 여행과 이야기를 SQLite에 저장합니다.

## 제공 기능

- 목적지 유무에 따른 추천 질문 4개 생성
- 가족 대화 PCM 스트리밍 수신 및 STT
- 30초 무음 후 맥락에 맞는 후속 질문과 TTS 반환
- 대화를 제목·요약·핵심 대화·지역·시각으로 정리
- 여행 생성·이어가기·종료 및 최근 여행 조회
- 정차 중 이야기 조회와 저장할 이야기 선택
- 최종 리포트와 이야기 보관함 데이터 조회

## 폴더 구조

```text
server/
├── api.py                 # FastAPI REST·WebSocket 엔드포인트
├── schemas.py             # 요청·응답 모델
├── ai/llm.py              # 질문 생성과 대화 정리
├── voice/                 # WebSocket, STT, TTS
├── recording/             # 기록 상태 관리와 SQLite 저장
├── demos/                 # 기능 확인용 실행 코드
└── API_CONTRACT.md        # Android–서버 전체 API 규격
```

## 로컬 실행

프로젝트 루트에서 실행합니다.

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r server/requirements.txt
cp .env.example .env
```

`.env`의 `OPENAI_API_KEY`에 사용할 키를 입력합니다. 실제 키가 들어 있는 `.env`는 GitHub에 올리지 않습니다.

```dotenv
OPENAI_API_KEY=your-api-key-here
OPENAI_MODEL=gpt-6.1-sol
OPENAI_STT_MODEL=gpt-transcribe
OPENAI_TTS_MODEL=gpt-4o-mini-tts
OPENAI_TTS_VOICE=marin
```

서버를 실행합니다.

```bash
uvicorn server.api:app --host 0.0.0.0 --port 8000
```

- 상태 확인: `http://127.0.0.1:8000/health`
- API 문서: `http://127.0.0.1:8000/docs`

## Android 연결

배포된 서버 주소가 `https://dorolog-ai-server.onrender.com`이라면 APK를 다음과 같이 빌드합니다.

```bash
cd Android
./gradlew :app:assembleRelease \
  -Pdorolog.apiBaseUrl=https://dorolog-ai-server.onrender.com/api/ \
  -Pdorolog.voiceUrl=wss://dorolog-ai-server.onrender.com/conversation
```

- REST 주소는 `https://`로 시작하고 `/api/`로 끝나야 합니다.
- 음성 WebSocket 주소는 `wss://`와 `/conversation`을 사용합니다.
- OpenAI API 키는 APK에 넣지 않고 서버에서만 관리합니다.
- 전체 요청·응답 JSON과 화면별 호출 순서는 [`API_CONTRACT.md`](API_CONTRACT.md)를 따릅니다.

## Render 배포

루트의 `render.yaml`을 이용해 배포합니다.

1. Render에서 GitHub 저장소를 연결하고 Blueprint를 생성합니다.
2. 환경 변수 `OPENAI_API_KEY`에 실제 키를 등록합니다.
3. 배포가 끝나면 `https://배포주소/health`에서 `{"status":"ok"}`를 확인합니다.
4. 확정된 배포 주소를 위 Android 빌드 옵션에 넣어 APK를 다시 빌드합니다.

`render.yaml`은 유료 512MB Web Service 한 대와 1GB 영구 디스크를 사용합니다. 자동 배포는 꺼져 있으므로 코드 변경 후 Render에서 직접 다시 배포해야 합니다.

## 데이터 저장

- 로컬 기본 경로: `data/dorolog.db`
- Render 경로: `/var/data/dorolog.db`
- Render에서는 1GB 영구 디스크에 SQLite 파일을 저장하므로 재배포·재시작 후에도 여행과 이야기가 유지됩니다.
- Web Service는 한 대만 실행하도록 설정되어 있습니다.

## 기능 확인

```bash
python -m server.demos.ai_demo
python -m server.demos.recording_demo
```
