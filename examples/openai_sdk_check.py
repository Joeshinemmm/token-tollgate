"""OpenAI 공식 SDK로 token-tollgate를 거쳐 모델을 부르는 확인 스크립트.

도구 쪽에서 바꾸는 것은 base_url과 api_key 둘뿐이다. 기본은 게이트웨이(8080)와 로컬 Ollama 모델 qwen3:4b.

    pip install openai
    PowerShell:  $env:TOLLGATE_VIRTUAL_KEY = "<대시보드에서 발급한 가상 키>"
    bash:        export TOLLGATE_VIRTUAL_KEY=<가상 키>
    python examples/openai_sdk_check.py [base_url] [model]

일반 응답 한 번, 스트리밍 한 번을 보내고 응답 헤더의 x-tollgate-request-id와 사용량, 걸린 시간을 찍는다.
요청 ID로 대시보드 요청 목록(태그 sdk-check)에서 같은 기록을 찾을 수 있다. 키는 출력하지 않는다.
"""

import os
import sys
import time

from openai import OpenAI

base_url = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080/v1"
model = sys.argv[2] if len(sys.argv) > 2 else "qwen3:4b"
key = os.environ.get("TOLLGATE_VIRTUAL_KEY")
if not key:
    sys.exit("TOLLGATE_VIRTUAL_KEY 환경 변수에 가상 키를 넣으세요.")

client = OpenAI(base_url=base_url, api_key=key, default_headers={"X-Tollgate-Tag": "sdk-check"})
messages = [{"role": "user", "content": "Say hello in one word."}]


def usage_text(usage) -> str:
    if usage is None:
        return "usage 없음"
    cached = getattr(getattr(usage, "prompt_tokens_details", None), "cached_tokens", None)
    return f"입력 {usage.prompt_tokens} (캐시 {cached if cached is not None else 0}) · 출력 {usage.completion_tokens}"


started = time.perf_counter()
raw = client.chat.completions.with_raw_response.create(model=model, messages=messages)
completion = raw.parse()
elapsed = time.perf_counter() - started
print(
    f"[일반]     요청 ID {raw.headers.get('x-tollgate-request-id')} | 모델 {completion.model} | "
    f"{usage_text(completion.usage)} | {elapsed:.2f}초 | 답 {completion.choices[0].message.content!r}"
)

started = time.perf_counter()
raw = client.chat.completions.with_raw_response.create(
    model=model, messages=messages, stream=True, stream_options={"include_usage": True}
)
first_content_at = None
usage = None
pieces = []
for chunk in raw.parse():
    if chunk.choices and chunk.choices[0].delta.content:
        if first_content_at is None:
            first_content_at = time.perf_counter()
        pieces.append(chunk.choices[0].delta.content)
    if chunk.usage:
        usage = chunk.usage
elapsed = time.perf_counter() - started
first = f"{first_content_at - started:.2f}초" if first_content_at else "없음"
print(
    f"[스트리밍] 요청 ID {raw.headers.get('x-tollgate-request-id')} | 첫 글자까지 {first} | "
    f"{usage_text(usage)} | {elapsed:.2f}초 | 답 {''.join(pieces)!r}"
)
