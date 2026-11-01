# 005 가짜 공급처

- 명세 작성: 2026-11-02, 승인: 2026-11-02 (9절의 세 항목 모두 승인), 구현·검증: 2026-11-02
- 선행: 004 요금표 (`model_prices`에 `fake/fake-small`, `fake/fake-large` 예시 단가 있음)
- 후속: 006 OpenAI 호환 중계, 007 Anthropic 호환 중계가 이 모듈을 상류로 쓴다

## 목표

실제 공급처 키 없이 OpenAI Chat Completions와 Anthropic Messages의 일반·스트리밍 응답, 오류, 스트림 끊김을 **결정적으로** 재현하는 별도 서버를 만든다. 사용량 숫자와 지연을 요청마다 지시할 수 있어서 006·007의 사용량 파싱·비용 계산·기록을 정확한 기대값으로 검증할 수 있고, 011 compose와 3단계 부하·장애 주입 시험에서도 같은 모듈을 쓴다.

## 완료 기준

1. `POST /v1/chat/completions`와 `POST /v1/messages`가 일반·스트리밍 응답을 공식 문서의 필드 이름·순서대로 돌려준다.
2. 모델명 지시(`in`, `cached`, `out`, `ttft`, `gap`, `err`, `abort`, `streamerr`)가 응답의 사용량·지연·오류·끊김에 그대로 반영된다. 지시가 없으면 `FAKE_*` 환경 변수 기본값을 쓴다.
3. `./gradlew :fake-provider:test`가 실제 키 없이 통과하고, jar를 띄워 curl로 여섯 시나리오(두 프로토콜 일반·스트리밍, 오류, 끊김)를 확인한다.
4. 요청·응답 본문과 인증 헤더를 로그에 남기지 않는다. 요청당 로그는 경로·모델 필드·스트림 여부·상태 코드 한 줄이다.

## 명세

### 1. 모듈 구조

| 파일 | 역할 |
|---|---|
| `fake-provider/src/main/kotlin/fakeprovider/FakeProviderApplication.kt` | 기존 파일 이동. `spring.config.name=fake-provider`를 기본 속성으로 둔다 |
| `fakeprovider/FakeProperties.kt` | `@ConfigurationProperties("fake")`: `apiKey`(선택), `defaults`(입력·캐시·출력 토큰, ttft, gap, 오류율) |
| `fakeprovider/directive/Directive.kt`, `DirectiveParser.kt` | 모델 문자열 → 기본 모델명 + 지시. 잘못된 지시는 `DirectiveException`(400) |
| `fakeprovider/text/FakeText.kt` | 출력 토큰 수만큼 단어 생성(고정 단어 목록 순환, 결정적) |
| `fakeprovider/common/StreamScript.kt`, `ResponseWriter.kt` | 프레임 순서·지연·끊김 공통 로직과 `text/event-stream` 프레임 단위 플러시 |
| `fakeprovider/common/Ids.kt`, `RandomFailures.kt` | 식별자 생성, `FAKE_ERROR_RATE` 판정 |
| `fakeprovider/openai/OpenAiFakeController.kt`, `OpenAiShapes.kt` | `POST /v1/chat/completions` 일반·스트리밍, 요청 해석, 응답 객체 |
| `fakeprovider/anthropic/AnthropicFakeController.kt`, `AnthropicShapes.kt` | `POST /v1/messages` 일반·스트리밍, 요청 해석, 응답·이벤트 객체 |
| `fakeprovider/error/FakeErrors.kt`, `FakeErrorHandler.kt` | 상태 코드 → 프로토콜별 오류 본문, 400 공통 처리 |
| `fakeprovider/auth/ApiKeyFilter.kt` | `FAKE_API_KEY`가 설정된 경우에만 `Authorization: Bearer`/`x-api-key` 검사 |
| `fake-provider/src/main/resources/fake-provider.yml` | 기존 `application.yml`을 이름 바꿈. 포트·actuator·`fake.*` ← `FAKE_*` 매핑 |
| `fake-provider/src/test/kotlin/fakeprovider/...` | 아래 "테스트" 절 |
| `.env.example`, `README.md` | `FAKE_*` 항목과 가짜 공급처 실행·지시 설명 |
| `docs/dev-log/000-phase1-plan.md` §8 | 계획과 달라진 점 기록 |

패키지를 `tokentollgate.fake`에서 `fakeprovider`로 바꾸는 이유: server의 `TokenTollgateApplication`은 `tokentollgate` 아래를 전부 스캔한다. 006에서 server 통합 테스트가 이 모듈을 테스트 classpath에 올리면 `tokentollgate.fake`의 `@RestController`가 게이트웨이 컨텍스트에 자동 등록되어 `/v1/chat/completions` 매핑이 충돌한다. 스캔 범위 밖 패키지에 두면 006이 필요한 빈만 명시적으로 등록(`@Import` + `/fake` 경로 접두사)할 수 있다. 설정 파일 이름을 `fake-provider.yml`로 바꾸는 이유도 같다. 두 모듈의 `application.yml`이 한 classpath에 있으면 먼저 발견되는 쪽만 읽힌다.

새 의존성: 없음(WebFlux, 코루틴, Jackson Kotlin 모듈, WebTestClient 모두 001에서 추가됨).

### 2. 모델명 지시

형식: `<기본 모델명>[:<키>=<값>[,<키>=<값>...]]`. 첫 `:` 앞이 기본 모델명이고 응답의 `model` 필드에는 **기본 모델명만** 넣는다(예: `fake-small:in=120,out=40` → `"model": "fake-small"`). 이유: 요금표의 모델명(`fake-small`)과 응답 모델명이 일치해야 006이 `model_served`로 단가를 찾을 수 있다. 실제 OpenAI도 요청한 별칭을 구체 id로 바꿔 돌려주므로 "요청 모델명 ≠ 응답 모델명"은 게이트웨이가 어차피 다뤄야 하는 경우다.

| 키 | 뜻 | 없을 때 기본값(환경 변수) |
|---|---|---|
| `in` | 입력 토큰 총량(캐시에서 읽은 분량 포함) | `FAKE_INPUT_TOKENS` = 100 |
| `cached` | 입력 중 캐시에서 읽은 토큰. `in` 이하 | `FAKE_CACHED_INPUT_TOKENS` = 0 |
| `out` | 출력 토큰 = 생성 단어 수 = 스트림 내용 조각 수. 최대 200,000 | `FAKE_OUTPUT_TOKENS` = 50 |
| `ttft` | 첫 프레임(일반 응답은 본문) 전 지연 ms | `FAKE_TTFT_MS` = 0 |
| `gap` | 내용 조각 사이 지연 ms (스트리밍만) | `FAKE_CHUNK_INTERVAL_MS` = 0 |
| `err` | 즉시 이 상태 코드(400~599)로 오류 응답 | 없음 |
| `abort` | N번째 내용 조각을 보낸 뒤 종료 프레임 없이 연결을 끊는다 (N ≥ 1, 스트리밍만) | 없음 |
| `streamerr` | N번째 내용 조각 뒤 오류 프레임/이벤트를 보내고 스트림을 끝낸다. 값 없이 쓰면 N = 1 | 없음 |

- 값은 0 이상 정수. 모르는 키, 정수가 아닌 값, `cached > in`, `err`가 400~599 밖, `abort`·`streamerr`가 1 미만이면 호출한 프로토콜 형식의 400. 이름과 값 주변 공백은 무시한다.
- `abort`·`streamerr`의 N이 내용 조각 수보다 크면 아무 일도 일어나지 않고 스트림이 정상 종료한다. 둘 다 같은 N이면 `abort`가 먼저다.
- 요청의 `max_tokens`(Anthropic 필수, OpenAI 선택. `max_completion_tokens`도 인정)가 `out`보다 작으면 출력을 거기서 자르고 `finish_reason: "length"` / `stop_reason: "max_tokens"`를 돌려준다. 그 외는 `"stop"` / `"end_turn"`.
- `FAKE_ERROR_RATE`(0~1, 기본 0): 지시와 무관하게 그 확률로 500(`server_error` / `api_error`)을 돌려준다. 3단계 부하 시험용이며 테스트는 0 또는 1로 고정해 결정적으로 검증한다.

### 3. 응답 형식 (아래 "확인한 공식 문서" 기준)

**OpenAI 일반**: `id`(`chatcmpl-` + 난수), `object: "chat.completion"`, `created`(초 단위 epoch), `model`(기본 모델명), `choices[0]`에 `index`, `message{role: "assistant", content, refusal: null, annotations: []}`, `logprobs: null`, `finish_reason`; `usage{prompt_tokens: in, completion_tokens: out, total_tokens, prompt_tokens_details{cached_tokens: cached, audio_tokens: 0}, completion_tokens_details{reasoning_tokens: 0, audio_tokens: 0, accepted_prediction_tokens: 0, rejected_prediction_tokens: 0}}`, `service_tier: "default"`, `system_fingerprint: "fp_fake"`.

**OpenAI 스트리밍**: 프레임마다 `data: {json}` 한 줄 + 빈 줄(콜론 뒤 공백 포함, 문서 예시와 같음). 순서는 역할 조각(`delta{role: "assistant", content: ""}`) → 내용 조각 × out(`delta{content}`) → 종료 조각(`delta{}`, `finish_reason`) → [`stream_options.include_usage: true`일 때만] 사용량 조각(`choices: []`, `usage{...}`) → `data: [DONE]`. `include_usage`를 켠 요청에서는 사용량 조각을 뺀 모든 조각에 `"usage": null`을 넣고, 켜지 않은 요청에서는 `usage` 키를 아예 넣지 않는다. `obfuscation` 필드는 넣지 않는다.

**Anthropic 일반**: `id`(`msg_` + 난수), `type: "message"`, `role: "assistant"`, `model`, `content: [{type: "text", text}]`, `stop_reason`, `stop_sequence: null`, `usage{input_tokens: in − cached, cache_creation_input_tokens: 0, cache_read_input_tokens: cached, output_tokens: out}`. Anthropic의 `input_tokens`는 캐시 읽기를 **뺀** 값이고 총 입력은 세 값의 합이다(문서 원문). OpenAI의 `prompt_tokens`는 캐시 포함 총량이다. 같은 `in`·`cached` 지시가 두 프로토콜에서 다른 숫자로 나가는 것이 의도이며, 007의 정규화(입력 총량 = 합)를 검증하는 재료가 된다. 응답 헤더 `request-id: req_...`를 넣는다.

**Anthropic 스트리밍**: 이벤트마다 `event: <type>` 줄 + `data: {json}` 줄 + 빈 줄. 순서는 `message_start`(`message.usage`에 `input_tokens`, 캐시 두 값, `output_tokens: 1`) → `content_block_start`(index 0, `{type: "text", text: ""}`) → `ping` 1회 → `content_block_delta` × out(`delta{type: "text_delta", text}`) → `content_block_stop` → `message_delta`(`delta{stop_reason, stop_sequence: null}`, `usage{output_tokens: out}` 누적값) → `message_stop`. `message_delta.usage`에는 문서 기본 예시와 같이 `output_tokens`만 넣는다. 그래서 007 파서는 입력 토큰을 `message_start`에서 가져와야 한다(계획 §3.2와 일치).

**지연**: `ttft`는 첫 프레임(또는 일반 응답 본문, 오류 응답)을 쓰기 전에, `gap`은 내용 조각 사이마다 `delay`한다. 프레임은 `ServerHttpResponse.writeAndFlushWith`로 하나씩 플러시해 게이트웨이가 재는 첫 토큰 시각이 실제 전송 시각과 맞게 한다.

**끊김(`abort=N`)**: N번째 내용 조각을 플러시한 뒤 본문 publisher를 오류로 끝내 Reactor Netty가 종료 프레임(`[DONE]`, `message_stop`, 청크 종료) 없이 연결을 닫게 한다.

**스트림 중 오류(`streamerr=N`)**: N번째 내용 조각 뒤에 OpenAI는 `data: {"error": {"message", "type": "server_error", "param": null, "code": null}}` 프레임을, Anthropic은 `event: error` + `{"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}`(문서 예시 그대로)를 보내고 스트림을 정상 종료한다. `[DONE]`·`message_stop`은 보내지 않는다.

### 4. 오류 본문

- OpenAI: `{"error": {"message", "type", "param", "code"}}`. `type`·`code`는 문서에 있는 값을 쓰고(429 `rate_limit_error`, 503 `service_unavailable_error` + `server_is_overloaded`, 400 `invalid_request_error`), 문서에 없는 상태 코드는 우리가 정한다: 401 `authentication_error` + `invalid_api_key`, 403 `permission_error`, 404 `not_found_error`, 그 외 4xx `invalid_request_error`, 5xx `server_error`. 게이트웨이는 상류 오류 본문을 그대로 전달하므로 이 값이 게이트웨이 로직을 바꾸지는 않는다.
- Anthropic: `{"type": "error", "error": {"type", "message"}, "request_id"}`. 상태 코드 → `type`은 문서 표 그대로(400 `invalid_request_error`, 401 `authentication_error`, 402 `billing_error`, 403 `permission_error`, 404 `not_found_error`, 409 `conflict_error`, 413 `request_too_large`, 429 `rate_limit_error`, 500 `api_error`, 504 `timeout_error`, 529 `overloaded_error`). 표에 없는 4xx는 `invalid_request_error`(문서가 "다른 4XX에도 쓰일 수 있다"고 명시), 5xx는 `api_error`.
- 요청 검증: JSON 객체가 아니거나 `model`이 문자열이 아니거나 `messages`가 비어 있으면 400. Anthropic은 `max_tokens`가 없어도 400(실제 API와 같이 필수).

### 5. 인증

`FAKE_API_KEY`가 비어 있으면(기본) 인증을 검사하지 않는다. 값이 있으면 `Authorization: Bearer <값>` 또는 `x-api-key: <값>`이 아닌 `/v1/` 아래 요청에 프로토콜 형식의 401을 돌려준다. 용도: 006·007 테스트에서 게이트웨이가 가상 키를 공급처 키로 바꿨고 가상 키를 흘리지 않았음을 확인한다. 키 값은 로그에 남기지 않고 비교는 상수 시간(`MessageDigest.isEqual`)으로 한다.

### 6. 설정 (`fake-provider.yml`)

```yaml
server.port: ${FAKE_PROVIDER_PORT:9090}
fake:
  api-key: ${FAKE_API_KEY:}
  defaults:
    input-tokens: ${FAKE_INPUT_TOKENS:100}
    cached-input-tokens: ${FAKE_CACHED_INPUT_TOKENS:0}
    output-tokens: ${FAKE_OUTPUT_TOKENS:50}
    ttft-ms: ${FAKE_TTFT_MS:0}
    chunk-interval-ms: ${FAKE_CHUNK_INTERVAL_MS:0}
    error-rate: ${FAKE_ERROR_RATE:0}
```

### 7. 테스트 (fake-provider 모듈, DB·Docker 불필요)

| 파일 | 내용 |
|---|---|
| `directive/DirectiveParserTest` (9) | 지시 없음 → 기본값, 모든 키, 값 없는 `streamerr` → 1, 공백 허용, 모르는 키·정수 아님·값 없음·상한 초과·`cached > in`·`err` 범위 밖·`abort`/`streamerr` 0·빈 모델명 → 예외 |
| `text/FakeTextTest` (2) | `out=N` → 단어 N개, 조각을 이으면 같은 내용, 단어 목록 순환 |
| `common/StreamScriptTest` (4) | 정상 순서, `streamerr`가 나머지를 오류 프레임으로 대체, `abort`가 N개 뒤 예외, N이 조각 수보다 크면 효과 없음 |
| `openai/OpenAiFakeApiTest` (9) | 일반 응답 필드·사용량, `max_tokens` 상한 → `length`, 스트림 프레임 순서와 `[DONE]`·`usage` 키 없음, `include_usage` 사용량 조각(`choices: []`, 다른 조각 `usage: null`), `err=429`·`503` 본문, `streamerr` → 오류 프레임 뒤 `[DONE]` 없음, `abort=2` → 내용 조각 2개 뒤 연결 끊김, `model`·`messages` 없음 → 400, 모르는 지시·잘못된 JSON → 400 |
| `anthropic/AnthropicFakeApiTest` (7) | 일반 응답 필드·캐시 사용량·`request-id`, `max_tokens` 상한 → `max_tokens`, 이벤트 순서와 누적 `output_tokens`, `err=529`(본문 `request_id` = 헤더), `streamerr` → `error` 이벤트, `abort` → 연결 끊김, `max_tokens`·`messages` 없음 → 400 |
| `auth/ApiKeyFilterTest` (4) | 키 설정 시 없음·틀림 → 401(프로토콜별 본문), `Bearer`·`x-api-key` 둘 다 통과, health는 키 불필요 |
| `TimingTest` (2) | `ttft=300` → 300ms 이상, `out=5,gap=50` → 200ms 이상(하한만 검사) |
| `ErrorRateTest` (1) | `fake.defaults.error-rate=1` → 두 프로토콜 모두 500 |
| `ConfigFileTest` (1) | `spring.config.name=fake-provider`로 `FAKE_OUTPUT_TOKENS`·`FAKE_TTFT_MS`가 `FakeProperties`에 닿음 |
| `HealthEndpointTest` (1) | 기존 테스트, 패키지만 이동 |

스트림 테스트는 WebTestClient 대신 `WebClient`로 바이트를 그대로 읽는다(`TestSupport.readSse`). Spring의 SSE 디코더를 거치면 `event:` 줄과 `data: ` 접두사, `[DONE]`이 사라져 원래 형식을 검증할 수 없고, 끊긴 연결에서 그때까지 받은 프레임을 보존해야 하기 때문이다.

### 8. curl 검증 (jar 실행 후)

1. OpenAI 일반: `fake-small:in=120,cached=20,out=8` → `usage` 120/8/128, `cached_tokens` 20
2. OpenAI 스트리밍 `-N`, `include_usage: true`: 역할 → 내용 8 → 종료 → 사용량 → `[DONE]`
3. Anthropic 일반: 같은 지시 → `input_tokens` 100, `cache_read_input_tokens` 20, `output_tokens` 8
4. Anthropic 스트리밍 `-N`: `message_start` … `message_delta`(`output_tokens` 8) → `message_stop`
5. 오류: `err=429`(OpenAI), `err=529`(Anthropic) 상태·본문
6. 끊김·지연: `abort=3` 스트림이 `[DONE]` 없이 끝남, `ttft=500`에서 `curl -w %{time_starttransfer}` ≥ 0.5초, `streamerr=2`

### 9. 승인이 필요한 항목 (2026-11-02 모두 승인됨)

1. 패키지 `tokentollgate.fake` → `fakeprovider`, 설정 파일 `application.yml` → `fake-provider.yml` (1절 이유).
2. 계획 §4.5에 없던 추가: `cached` 지시와 `FAKE_CACHED_INPUT_TOKENS`, `streamerr=N` 값, `FAKE_API_KEY` 검사, `max_tokens` 상한 반영, 응답 `model` = 기본 모델명.
3. OpenAI 오류 `type` 중 문서에 없는 상태 코드(401·403·404·500 등)의 값을 우리가 정하는 것(4절).

## 확인한 공식 문서 (2026-11-02)

OpenAI (developers.openai.com, 페이지 본문 그대로 확인):
- Chat Completions 생성 레퍼런스: `https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create`. 일반 응답 예시(`choices[].message{role, content, refusal, annotations}`, `logprobs: null`, `usage.prompt_tokens_details.cached_tokens`, `completion_tokens_details.*`, `service_tier`), 스트리밍 예시(`data: ` 뒤 공백, 역할 조각 → 내용 조각 → `delta: {}` + `finish_reason: "stop"` → `data: [DONE]`), `stream_options.include_usage` 설명 원문("an additional chunk will be streamed before the `data: [DONE]` message … `choices` field will always be an empty array. All other chunks will also include a `usage` field, but with a null value").
- 조각 객체: `https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events`. `object: "chat.completion.chunk"`, `usage`는 `include_usage` 때만 존재하며 마지막 조각 외에는 null, `obfuscation`은 선택.
- 오류 코드 안내: `https://developers.openai.com/api/docs/guides/error-codes`. 503 `service_unavailable_error` + `server_is_overloaded`, 429 "Slow down" `rate_limit_error` + `slow_down`, 400 `invalid_request_error`. 401·500과 일반 429 행에는 type·code가 적혀 있지 않다. 오류 본문 JSON 예시는 어느 페이지에도 없고 `error.code`·`error.type`·`error.param` 이름만 언급된다. 본문 모양 `{"error": {"message", "type", "param", "code"}}`는 계획 §3.1(OpenAPI 명세)에서 확인한 것을 따른다.
- 찾지 못한 것: `include_usage` 사용량 조각의 JSON 예시(설명 문장만 있음), 401·500의 type/code.

Anthropic (platform.claude.com, 페이지 본문 그대로 확인):
- 스트리밍: `https://platform.claude.com/docs/en/build-with-claude/streaming`. 기본 예시의 전체 이벤트 순서와 JSON(`message_start.message.usage` = `{"input_tokens": 25, "output_tokens": 1}`, `message_delta.usage` = `{"output_tokens": 15}`, `ping`, `content_block_stop`, `message_stop`), `error` 이벤트 예시(`overloaded_error`, "Overloaded"), "`message_delta`의 usage는 누적" 경고문, "모르는 이벤트는 그대로 처리" 문장.
- Messages 레퍼런스: `https://platform.claude.com/docs/en/api/messages`. 응답 필드 순서(`type, id, container, content, diagnostics, model, role, stop_details, stop_reason, stop_sequence, usage`), `usage` 필드와 null 허용 여부(`cache_creation_input_tokens`·`cache_read_input_tokens` null 허용, `input_tokens`·`output_tokens` 필수), "총 입력 = `input_tokens` + `cache_creation_input_tokens` + `cache_read_input_tokens`" 원문, `stop_reason` 값 7종, `max_tokens`·`messages`·`model`만 optional 표시가 없음(필수로 해석), `MessageDeltaUsage`에 `input_tokens`는 null 허용·`output_tokens`는 필수.
- 오류: `https://platform.claude.com/docs/en/api/errors`. 본문 예시(`type: "error"`, `error{type, message}`, `request_id`), 상태 코드 → type 표 11개, `request-id` 헤더 설명, "스트리밍 중 오류는 200 뒤 이벤트로 온다" 문장.
- 헤더: `https://platform.claude.com/docs/en/api/overview`(getting-started에서 이동). `Authorization: Bearer` 필수("unless `x-api-key` is set"), `x-api-key`는 레거시 대체, `anthropic-version`은 개요 표에서 필수·Messages 레퍼런스에서는 optional로 상충. 가짜 공급처는 `anthropic-version`을 요구하지 않는다(게이트웨이가 전달하는지는 007에서 검증).
- 찾지 못한 것: `message_start.message.usage`에 캐시 필드가 포함된 스트리밍 예시(레퍼런스 정의만 있음). 가짜 공급처는 레퍼런스 정의대로 캐시 두 필드를 `message_start`에 넣는다.

## 결정과 이유

- 응답 객체를 data class 대신 순서 있는 맵으로 만든다. OpenAI 조각은 `include_usage` 여부에 따라 `usage` 키가 "없음"과 "null"로 갈리는데, data class와 직렬화 설정으로는 한 타입 안에서 이 둘을 가르기 어렵다. 맵이면 키의 유무와 순서를 문서 예시 그대로 맞출 수 있다.
- 컨트롤러는 `suspend fun` 대신 `mono<Void> { }`로 감싸고 `ServerHttpResponse`에 직접 쓴다. 한 메서드가 JSON 응답, 오류 응답, 스트림을 모두 내보내야 하는데 반환 타입으로는 셋을 함께 표현할 수 없다. 스트림은 `writeAndFlushWith`로 프레임마다 플러시해서 `ttft`·`gap`이 실제 전송 시각에 나타난다.
- 끊김은 본문 publisher의 오류 신호로 구현했다. 응답이 이미 커밋된 뒤 오류가 나면 Reactor Netty가 청크 종료 없이 연결을 닫는다. curl은 종료 코드 18(전송 중 연결 끊김), WebClient는 `PrematureCloseException`으로 받는 것을 확인했으므로 네이티브 연결을 직접 닫는 방법은 쓰지 않았다.
- 요청 본문은 DTO 대신 `JsonNode`로 읽는다. 두 API의 요청 필드는 많고 계속 늘지만 가짜가 보는 것은 `model`, `messages`, `stream`, `max_tokens`, `stream_options.include_usage`뿐이다. 모르는 필드를 전부 허용하면서 필요한 것만 꺼내는 데 트리가 가장 단순하다.
- `FAKE_ERROR_RATE` 판정은 지시 해석 뒤, 다른 응답보다 먼저 한다. 부하 시험에서 "어떤 요청이든 일정 비율로 실패"를 흉내 내는 목적이므로 `err`·`abort` 같은 지시보다 우선한다.
- 스트림 테스트는 `WebClient`로 원시 바이트를 읽는다(7절). 서버 모듈의 006·007 테스트도 같은 방식으로 상류 프레임을 검증할 수 있다.
- 006에서 server 테스트에 올리는 방식은 "`@Import`로 가짜 컨트롤러 두 개를 등록하고 `WebFluxConfigurer.addPathPrefix("/fake", 패키지 조건)`로 경로를 옮기며, 테스트 전용 Security 필터 체인이 `/fake/**`를 허용"으로 잡는다. 별도 프로세스나 별도 컨텍스트는 server classpath의 R2DBC·Flyway·Security 자동 설정이 따라붙어 더 복잡하다. 실제 확인은 006에서 한다.

## 겪은 문제

- Kotlin은 블록 주석이 중첩되므로 KDoc 안의 `` `/v1/**` ``가 새 주석을 열어 "Unclosed comment"와 엉뚱한 구문 오류가 났다. 주석 문구를 "paths under /v1"로 바꿨다.
- `companion object`의 `const val`은 그 클래스 자신의 어노테이션(`@SpringBootTest(properties = [...])`)에서 쓸 수 없다. 파일 최상위 `private const val`로 옮겼다.
- 테스트 작성 실수 둘: 파서 테스트의 기본값이 `cached = 5`인데 `in = 3`을 넣어 "cached must not exceed in"이 났고, 타이밍 테스트에서 `out=5`의 Anthropic 프레임 수를 9로 적었다(실제 11 = 머리 3 + 내용 5 + 꼬리 3). 둘 다 테스트를 고쳤다.
- `abort`를 쓰면 서버 로그에 ERROR 두 줄이 남는다(`HttpWebHandlerAdapter`의 "already committed"와 Reactor Netty의 "Error finishing response. Closing connection"). 의도한 동작이고 연결이 실제로 닫혔음을 보여 주는 흔적이라 그대로 두었다(남은 문제 참고).
- 처음 `./gradlew build`는 1초 만에 끝났다. server 모듈에 변경이 없어 Gradle이 `:server:test`를 최신 상태로 건너뛰었기 때문이다. 결과를 "전체 테스트 통과"로 적으려고 `./gradlew test --rerun-tasks`로 다시 돌렸다.

## 실행한 검증과 결과

| 검증 | 명령 | 결과 |
|---|---|---|
| 모듈 테스트 | `./gradlew :fake-provider:test` | 40개 통과, 실패 0, 13초(컴파일 포함 두 번째 실행). 첫 실행은 위 테스트 실수 2건으로 실패 후 수정 |
| jar 기동 | `java -jar fake-provider-0.1.0-SNAPSHOT.jar` (`FAKE_OUTPUT_TOKENS=3`) | 1.5초에 9090 포트 기동(`fake-provider.yml`이 읽힘). 지시 없는 요청의 `completion_tokens`가 3 → 환경 변수가 yml을 거쳐 반영됨 |
| 1 OpenAI 일반 | `fake-small:in=120,cached=20,out=8` | 200, `model` fake-small, 내용 단어 8개, `usage` 120/8/128, `cached_tokens` 20, `finish_reason` stop |
| 2 OpenAI 스트리밍 | 같은 지시 + `include_usage` | 역할 조각 → 내용 8 → 종료 조각 → 사용량 조각(`choices: []`) → `data: [DONE]`. 사용량 조각 외 모두 `"usage":null` |
| 3 Anthropic 일반 | 같은 지시 | 200, `request-id` 헤더, `input_tokens` 100, `cache_read_input_tokens` 20, `output_tokens` 8, `stop_reason` end_turn |
| 4 Anthropic 스트리밍 | 같은 지시 | `message_start`(usage 100/0/20/1) → `content_block_start` → `ping` → delta 8 → `content_block_stop` → `message_delta`(`output_tokens` 8) → `message_stop` |
| 5 오류 | `err=429`, `err=529` | 429 `rate_limit_error` "Rate limit reached for requests"; 529 `overloaded_error` "Overloaded" + `request_id` |
| 6a 끊김 | `out=5,abort=3` 스트림 | 역할 조각 + 내용 3개 뒤 연결 종료, curl 종료 코드 18, `[DONE]` 없음 |
| 6b 지연 | `ttft=500,out=1` | `time_starttransfer` 0.505초 |
| 6c 스트림 중 오류 | `out=5,streamerr=2` (Anthropic) | delta 2개 뒤 `event: error` `overloaded_error`, `message_stop` 없음, HTTP 200 |
| 8 잘못된 지시 | `bogus=1` | 400 `invalid_request_error`, "Invalid model directive: unknown directive 'bogus'", `param` model |
| 로그 | 요청 10건 | 한 줄씩 경로·모델 필드·스트림 여부·상태만 기록. 본문·헤더 없음 |

전체 테스트 재실행 `./gradlew test --rerun-tasks`: BUILD SUCCESSFUL 27초, server 52개 + fake-provider 40개 = 92개 통과, 실패 0 (server 통합 테스트는 Testcontainers PostgreSQL 18, Docker Desktop 엔진 29.9.2).

측정 조건: Windows 11 Home, JDK Temurin 25.0.5+8, Gradle 9.8.2, curl(Windows 기본 curl.exe), 2026-11-02.

## 남은 문제

- `abort` 한 번마다 ERROR 로그 두 줄이 남는다. 부하 시험에서 끊김을 많이 넣으면 로그가 시끄러워지므로 그때 로그 레벨 조정이나 전용 표시를 검토한다.
- 흉내 내는 범위는 텍스트 블록 하나짜리 응답뿐이다. 도구 호출, 확장 사고, 이미지, `n > 1`, 중지 시퀀스, 캐시 쓰기 토큰은 없다. 006·007에 필요해지면 그때 더한다.
- `abort`는 스트리밍에만 적용된다. 일반 응답을 본문 없이 끊는 시나리오는 없다. 느린 상류 시간 초과는 `ttft`를 크게 주어 재현한다.
- 타이밍 테스트는 하한만 검사한다. 느린 환경에서도 통과하지만 "지연이 너무 길지 않다"는 검증하지 않는다.
- `GET /v1/models`는 제공하지 않는다. 게이트웨이가 요금표로 직접 답하므로 1단계에는 필요 없다.
- 006에서 server 테스트에 이 모듈을 올리는 방식(결정과 이유의 마지막 항목)은 아직 코드로 확인하지 않았다.
