# Hyundai-pleos-app-contest
가족의 대화를 잇고, 그 이야기를 지도 위에 남기는 차량용 가족 대화 서비스

## AI 서버 핵심 기능

목적지를 입력하면 가족 대화 주제 2개와 첫 질문을 만듭니다. 최근 대화를 입력하면 30초 무음 상황에 이어갈 질문을 제안하고, 대화를 이야기별 제목·요약·참여자·가보고 싶은 장소로 정리합니다.

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r server/requirements.txt
cp .env.example .env
python -m server.demo
```

`.env`의 `OPENAI_API_KEY`에 발급받은 API 키를 입력해야 합니다.
