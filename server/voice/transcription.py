"""OpenAI Realtime VAD·STT 연결을 담당한다."""

import json
import os
from base64 import b64encode
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from openai import AsyncOpenAI


load_dotenv()

STT_MODEL = os.getenv("OPENAI_STT_MODEL", "gpt-transcribe")

client = AsyncOpenAI()


@asynccontextmanager
async def open_transcription():
    """24kHz PCM을 받는 OpenAI 실시간 STT 세션을 연다."""
    async with client.realtime.connect(
        extra_query={"intent": "transcription"},
        max_retries=0,
    ) as connection:
        await connection.send_raw(
            json.dumps(
                {
                    "type": "session.update",
                    "session": {
                        "type": "transcription",
                        "audio": {
                            "input": {
                                "format": {
                                    "type": "audio/pcm",
                                    "rate": 24_000,
                                },
                                "transcription": {
                                    "model": STT_MODEL,
                                    "languages": ["ko"],
                                    "prompt": "가족이 차량에서 나누는 자연스러운 한국어 대화",
                                },
                                "turn_detection": {
                                    "type": "server_vad",
                                    "threshold": 0.5,
                                    "prefix_padding_ms": 300,
                                    "silence_duration_ms": 600,
                                },
                            }
                        },
                    },
                },
                ensure_ascii=False,
            )
        )

        while True:
            event = await receive_transcription_event(connection)
            if event.get("type") == "session.updated":
                break
            if event.get("type") == "error":
                error = event.get("error", {})
                raise RuntimeError(error.get("message", "STT 세션을 시작할 수 없습니다."))

        yield connection


async def send_transcription_audio(connection, pcm: bytes) -> None:
    """Android에서 받은 PCM을 OpenAI STT 세션에 보낸다."""
    await connection.send_raw(
        json.dumps(
            {
                "type": "input_audio_buffer.append",
                "audio": b64encode(pcm).decode("ascii"),
            }
        )
    )


async def receive_transcription_event(connection) -> dict:
    """OpenAI STT 세션의 다음 이벤트를 JSON으로 받는다."""
    return json.loads(await connection.recv_bytes())
