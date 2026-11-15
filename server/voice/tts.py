import os
from collections.abc import AsyncIterator

from dotenv import load_dotenv
from openai import AsyncOpenAI


load_dotenv()

TTS_MODEL = os.getenv("OPENAI_TTS_MODEL", "gpt-4o-mini-tts")
TTS_VOICE = os.getenv("OPENAI_TTS_VOICE", "marin")

# 24kHz, 모노, 16비트 PCM의 200ms 분량이다.
PCM_CHUNK_BYTES = 24_000 * 2 // 5

client = AsyncOpenAI()


async def stream_speech_pcm(text: str) -> AsyncIterator[bytes]:
    """후속 질문을 Android에서 바로 재생할 수 있는 24kHz PCM으로 변환한다."""
    async with client.audio.speech.with_streaming_response.create(
        model=TTS_MODEL,
        voice=TTS_VOICE,
        input=text,
        instructions="따뜻하고 자연스러운 한국어로, 너무 빠르지 않게 질문을 읽어 주세요.",
        response_format="pcm",
        stream_format="audio",
    ) as response:
        pending = b""

        async for audio in response.iter_bytes(chunk_size=PCM_CHUNK_BYTES):
            pending += audio

            while len(pending) >= PCM_CHUNK_BYTES:
                yield pending[:PCM_CHUNK_BYTES]
                pending = pending[PCM_CHUNK_BYTES:]

        if len(pending) % 2 != 0:
            raise RuntimeError("TTS가 16비트 PCM 형식이 아닌 오디오를 반환했습니다.")

        if pending:
            yield pending
