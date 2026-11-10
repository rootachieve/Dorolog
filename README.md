# Hyundai-pleos-app-contest
가족의 대화를 잇고, 그 이야기를 지도 위에 남기는 차량용 가족 대화 서비스

## AI 서버 핵심 기능

목적지를 입력하면 가족 대화 주제 2개와 첫 질문을 만듭니다. 최근 대화를 입력하면 30초 무음 상황에 이어갈 질문을 제안하고, 대화를 이야기별 제목·요약·참여자·가보고 싶은 장소로 정리합니다.

`server/recording.py`는 여정별로 기록을 시작하고, 음성 인식된 대화를 누적하고, 현재 이야기 저장과 기록 종료를 관리합니다. 현재 MVP에서는 실행 중인 Python 메모리에 기록을 보관합니다.

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r server/requirements.txt
cp .env.example .env
python -m server.demo
```

`.env`의 `OPENAI_API_KEY`에 발급받은 API 키를 입력해야 합니다.

기록 시작부터 이야기 저장과 종료까지 확인하려면 기록 관리 데모를 실행합니다.

```bash
python -m server.recording_demo
```

FastAPI 서버를 실행합니다.

```bash
uvicorn server.api:app --reload
```

서버 실행 후 `http://127.0.0.1:8000/docs`에서 각 API를 직접 호출할 수 있습니다.
