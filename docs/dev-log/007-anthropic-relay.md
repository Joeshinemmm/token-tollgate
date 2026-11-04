# 007 Anthropic 호환 중계

- 명세 작성: 2026-11-05, 승인: 2026-11-05 (10절의 여섯 항목 모두), 구현·검증: 2026-11-05
- 선행: 006 OpenAI 호환 중계(공급처 설정·기록·프레임 분할기), 005 가짜 공급처(`/v1/messages` 흉내), 004 요금표
- 후속: 008 사용량 조회 API는 두 프로토콜의 기록을 함께 집계한다. 실제 Anthropic 키 연동 시험(계획 §6)은 2026-11-05 사용자가 직접 실행해 성공했다(아래 "실제 Anthropic 키 시험 결과")

## 목표

가상 키로 인증된 `POST /v1/messages` 요청을 `type: anthropic` 공급처(가짜 공급처, Anthropic)로 중계한다. 일반 응답은 그대로 돌려주고 스트리밍은 바이트를 한 글자도 바꾸지 않고 흘려보내면서 `message_start`·`message_delta`에서 사용량을 읽는다. Anthropic이 세 값으로 나눠 주는 입력 토큰을 우리 기록의 두 컬럼(입력 총량, 캐시 읽기)으로 정규화해 004의 비용 계산에 넣는다. 006의 중계 코드에서 OpenAI 전용 부분을 떼어내 두 프로토콜이 한 엔진을 쓰게 한다.

## 완료 기준

1. 일반/스트리밍 두 경우에서 가짜 공급처가 지시한 `in=120,cached=20,out=8`이 기록에 입력 120·캐시 20·출력 8로 들어가고 비용이 OpenAI 경로와 같은 0.00011800이 된다(같은 지시, 같은 단가면 같은 비용).
2. 스트리밍 응답은 상류 바이트와 동일하다. 프레임을 걸러 내거나 고치지 않는다.
3. 상류 오류(529 등)·스트림 중 `error` 이벤트·끊김·시간 초과·클라이언트 중단이 각각의 `outcome`으로 기록되고, 게이트웨이 자체 오류는 Anthropic 오류 형식으로 나간다.
4. 006의 OpenAI 통합 테스트 13개가 수정 없이 그대로 통과한다(리팩터링의 회귀 확인).
5. 통합 테스트는 같은 JVM의 가짜 공급처만 쓰고 실제 키 없이 전부 통과한다.

## 명세

### 1. 공급처 설정

같은 이름을 종류별로 한 번씩 등록할 수 있게 한다(식별자는 이름 + 종류). 가짜 공급처 프로세스 하나가 두 프로토콜을 다 말하므로, 기록과 요금표의 `provider`는 `fake` 하나로 두고 라우팅만 종류별로 한다.

```yaml
tollgate:
  providers:
    - name: fake
      type: openai
      ...(006 그대로)
    - name: fake
      type: anthropic
      base-url: ${FAKE_PROVIDER_URL:http://localhost:9090}
      api-key-env: FAKE_API_KEY
      anonymous: true
      models: ["fake-*"]
    - name: ollama ... (006 그대로)
    - name: openai ... (006 그대로)
    - name: anthropic
      type: anthropic
      base-url: https://api.anthropic.com
      api-key-env: ANTHROPIC_API_KEY
      default: true
      models: ["claude-*"]
```

- `ProviderRegistry`의 중복 검사는 (이름, 종류) 기준으로 바꾸고 클라이언트 맵도 같은 키를 쓴다. `route(type, model)`은 이미 종류별로 고르므로 그대로다.
- `anthropic`은 자기 종류의 기본 공급처다. `/v1/messages`로 오는 요청은 Anthropic 클라이언트가 보낸 것이므로 패턴에 안 맞는 이름도 Anthropic으로 보낸다. 키가 비어 있으면 503 `provider_disabled`.
- `GET /admin/v1/providers`는 종류가 다른 같은 이름을 두 항목으로 보여 준다(응답에 `type`이 이미 있다).

### 2. 공통 중계 엔진으로 정리

006의 `OpenAiRelay`(308줄)는 흐름(라우팅 → 상류 호출 → 일반/스트림 쓰기 → 종료 판정 → 기록)과 OpenAI 형식 지식(본문 검사, 사용량 파싱, 조각 해석, 오류 본문)이 한 파일에 있다. 흐름은 두 프로토콜이 같고 형식 지식만 다르므로 둘을 나눈다.

| 파일 | 역할 |
|---|---|
| `relay/ProtocolAdapter.kt` | 인터페이스 `ProtocolAdapter`(`protocol`, `providerType`, `inspect`, `prepareUpstreamHeaders`, `parsePlain`, `streamObserver`, `errorBody`, `errorFrame`)와 `StreamObserver`(`observe`, `model`, `usage`, `done`, `sawError`, `errorType`, `firstContentNanos`), `InspectedRequest`, `PlainOutcome` |
| `relay/RelayEngine.kt` | 006 `OpenAiRelay`의 흐름을 어댑터 호출로 바꾼 것. 동작은 그대로 |
| `relay/SseFrames.kt` | `relay/openai`에서 공통 패키지로 이동 |
| `relay/openai/OpenAiAdapter.kt` | 기존 `OpenAiRequestInspector`·`OpenAiStreamObserver`·`OpenAiUsageParser`·`RelayErrors`의 OpenAI 본문을 묶는다 |
| `relay/anthropic/AnthropicAdapter.kt` | 아래 3~6절 |

`OpenAiRelay.kt`는 삭제한다. `OpenAiChatController`는 `engine.relay(openAiAdapter, …)`를 부른다. 완료 기준 4가 이 정리의 회귀 테스트다.

### 3. Anthropic 요청 검사

- 본문 JSON에서 `model`(문자열 필수, 없으면 400)과 `stream`만 읽는다. `max_tokens`는 검사하지 않고 상류의 400을 그대로 전달한다(상류가 판단할 일).
- 본문은 **항상 그대로** 상류로 보낸다. Anthropic 스트림은 요청 옵션 없이도 `message_start`·`message_delta`에 사용량을 담으므로 OpenAI처럼 끼워 넣을 것이 없다.
- 헤더: 006의 규칙 그대로 전달하되, 공급처 키는 `Authorization: Bearer <키>`로 넣는다(개요 표의 기본 방식. `x-api-key`는 레거시). `anthropic-version`·`anthropic-beta`는 클라이언트 값을 그대로 전달하고, `anthropic-version`이 없으면 `2023-06-01`을 넣는다. 공식 문서가 이 헤더를 개요에서는 필수, Messages 레퍼런스에서는 선택이라고 상충되게 적고 있어 없을 때만 보강한다. 값은 스트리밍·오류 페이지의 curl 예시가 쓰는 것이다.

### 4. 사용량 정규화

Anthropic 응답의 `usage`는 입력을 세 값으로 나눈다. 문서 원문: "Total input tokens in a request is the summation of `input_tokens`, `cache_creation_input_tokens`, and `cache_read_input_tokens`."

| 기록 컬럼 | 값 |
|---|---|
| `input_tokens` | `input_tokens + cache_creation_input_tokens + cache_read_input_tokens` (null은 0) |
| `cached_input_tokens` | `cache_read_input_tokens` |
| `output_tokens` | `output_tokens` |

`cache_creation_input_tokens`는 입력 단가로 계산된다. 실제로는 1.25배(5분)·2배(1시간)이지만 004 요금표에 캐시 쓰기 단가가 없어 그만큼 적게 잡힌다. 004의 남은 문제와 같은 항목이다.

**스트리밍**: `message_start.message.usage`를 기준값으로 두고, 이후 `message_delta.usage`에 있는 필드만 덮어쓴다(누적값, 계획 §3.2). `message_start`에 `usage`가 없으면(확장 사고 예시) `message_delta`에 `input_tokens`가 올 때까지 입력은 모른다. 스트림이 `message_stop`으로 끝났고 입력·출력을 모두 알면 `usage_source = stream`, 입력을 끝까지 모르면 `none`.

**끊긴 스트림**: 006과 같이 `usage_source = none`, 토큰 0, 비용 null. `message_delta`까지 받은 누적 출력 토큰은 "받은 만큼 확정"(2단계 규칙)의 재료지만 1단계에서는 쓰지 않는다. 두 프로토콜의 기록 의미를 같게 두기 위해서다.

### 5. 스트림 관찰

이벤트마다 `event:` 줄과 `data:` JSON이 온다. 관찰기는 `data`의 `type`으로 판단하고 **모든 프레임을 그대로 전달**한다(걸러 내는 것이 없다).

| 이벤트 | 하는 일 |
|---|---|
| `message_start` | `message.model` → `model_served`, `message.usage` → 기준 사용량 |
| `content_block_delta` | 첫 번째가 오면 `ttft_ms` (텍스트·도구 입력·사고 델타 구분 없이) |
| `message_delta` | `usage`의 필드를 덮어씀. `delta.stop_reason`은 기록 컬럼이 없어 읽지 않음 |
| `message_stop` | 정상 종료 |
| `error` | `error.type` → `error_type`, `outcome = upstream_error` |
| `ping`, 모르는 이벤트 | 전달만 |

`message_stop` 없이 끝나면 `stream_interrupted`, 프레임 간격 초과면 `upstream_timeout`. 게이트웨이가 보내는 오류 이벤트는 `event: error` + `{"type": "error", "error": {"type": "api_error" | "timeout_error", "message": "... (stream_interrupted)"}}`다. Anthropic 오류에는 `code` 필드가 없어 사유를 메시지에 넣는다.

### 6. 게이트웨이 자체 오류의 Anthropic 형식

본문 `{"type": "error", "error": {"type", "message"}, "request_id": "<요청 id>"}`. 상태 코드는 006과 같고 `type`만 문서 표에 맞춘다.

| 경우 | 상태 | `error.type` |
|---|---|---|
| 본문·모델명 오류 | 400 | `invalid_request_error` |
| 공급처 없음 | 404 | `not_found_error` |
| 연결 실패 | 502 | `api_error` |
| 공급처 비활성 | 503 | `api_error` |
| 응답 시간 초과 | 504 | `timeout_error` |
| 그 외 | 500 | `api_error` |

502·503은 Anthropic 표에 없는 상태라 `api_error`를 쓴다. `error_type` 기록 값은 006과 같은 코드(`model_not_found`, `provider_disabled`, `upstream_unreachable`, `upstream_timeout`, `stream_interrupted`)다.

### 7. 파일

server 모듈:

| 파일 | 역할 |
|---|---|
| `relay/ProtocolAdapter.kt`, `relay/RelayEngine.kt`, `relay/SseFrames.kt`(이동) | 2절 |
| `relay/openai/OpenAiAdapter.kt` | 2절. `OpenAiRelay.kt` 삭제, `OpenAiStreamObserver`는 `StreamObserver` 구현 |
| `relay/anthropic/AnthropicRequestInspector.kt` | 3절 |
| `relay/anthropic/AnthropicUsageParser.kt` | 4절 정규화와 `message_delta` 덮어쓰기(`Accumulator`) |
| `relay/anthropic/AnthropicStreamObserver.kt` | 5절 |
| `relay/anthropic/AnthropicErrors.kt` | 6절 매핑, 오류 이벤트 |
| `relay/anthropic/AnthropicAdapter.kt`, `AnthropicMessagesController.kt` | `POST /v1/messages`, `anthropic-version` 보강 |
| `upstream/ProviderRegistry.kt` | (이름, 종류) 식별 |
| `application.yml`, `.env.example`, `README.md` | 1절 설정, `ANTHROPIC_API_KEY`(빈 값), 중계 절에 Anthropic 경로·헤더·정규화 설명 |
| `docs/dev-log/000-phase1-plan.md` §8 | 변경 이력 |

새 의존성: 없음.

### 8. 테스트

테스트용 공급처 목록(`FakeUpstreamInitializer`)에 `type: anthropic` 항목을 더한다: `fake`, `fake-nokey`, `fake-slow`(`response-timeout: 500ms`), 그리고 비활성 `anthropic`(절대 설정되지 않는 변수 이름, `claude-*`, 기본 공급처).

| 파일 | 내용 |
|---|---|
| `relay/anthropic/AnthropicRelayTest` (통합, 11) | 1 일반 `fake-relay:in=120,cached=20,out=8`: 200, 본문 그대로(`usage.input_tokens` 100, `cache_read_input_tokens` 20), 가짜의 `request-id` 헤더 전달, 기록 120/20/8, `cost` 0.00011800, `response`, `protocol` anthropic. 2 스트림: 14프레임의 이벤트 순서, 가짜를 직접 부른 결과와 메시지 id만 치환하면 바이트 동일, 기록 `stream` 120/20/8, `ttft_ms` ≥ 지시값, `model_served` fake-relay. 3 상류 529: 상태·본문·`request_id` 그대로, `upstream_error`/`overloaded_error`. 4 `abort=2`: 머리 3 + 내용 2 + 게이트웨이 `error` 이벤트(`api_error`, 메시지에 stream_interrupted), `message_stop` 없음. 5 `streamerr=2`: 가짜의 `error` 이벤트 그대로, `error_type` overloaded_error. 6 `slow-model:ttft=2000` → 504 `timeout_error`, 기록 `timeout`. 7 `claude-x` → 비활성 기본 공급처 → 503 `api_error`(provider_disabled). 8 `nokey-model` → 가짜의 401 `authentication_error` 그대로. 9 클라이언트 중단 → `client_abort`. 10 `model` 없음 → 400 `invalid_request_error`(Anthropic 형식, `request_id` 포함), 기록 없음. 11 `max_tokens` 없음 → 가짜의 400이 그대로 전달, 기록 `upstream_error`/`invalid_request_error` |
| `relay/anthropic/AnthropicStreamObserverTest` (단위, 5) | 전체 스트림(모델·정규화·첫 내용·종료), `usage` 없는 delta 유지, 입력 없는 `message_start` 뒤 delta로 보강, `error` 이벤트, 모르는 이벤트·주석·JSON 아님 통과 |
| `relay/anthropic/AnthropicUsageParserTest` (단위, 3) | 세 값 합산, null·누락 0 처리와 입력·출력 없으면 null, `Accumulator` 덮어쓰기 |
| `relay/anthropic/AnthropicRequestInspectorTest` (단위, 3) | 본문 바이트 그대로, `max_tokens` 미검사, `model` 없음·잘못된 JSON·배열·빈 본문 거절 |
| `relay/anthropic/AnthropicAdapterTest` (단위, 4) | `anthropic-version` 없을 때만 보강, 오류 종류 매핑과 `request_id`, 스트림 오류 이벤트 모양(`request_id` 없음), 일반 응답 파싱 |
| `upstream/ProviderRegistryTest` (수정, 6) | 같은 이름 다른 종류 허용과 종류별 라우팅·기본 공급처, 같은 이름 같은 종류 거절 |
| 기존 `OpenAiRelayTest` 13, `SseFramesTest` 5(패키지 이동) | 수정 없이 통과(완료 기준 4) |

### 9. 수동 확인

1. compose PostgreSQL + 가짜 공급처 jar + 서버 jar: `/v1/messages` 일반·스트리밍·529·끊김, 키 없는 Anthropic 모델, 정리 뒤의 OpenAI 경로, 공급처 목록을 curl로 보내고 `usage_records`에서 `protocol = anthropic` 행과 비용을 확인한다.
2. **실제 Anthropic 키 시험(사용자 확인 후, 별도 단계)**: 키는 대화에 적지 않는다. 사용자가 자기 터미널에서 `$env:ANTHROPIC_API_KEY`를 설정하고 서버를 띄우면, 저는 가상 키로 curl만 보낸다. 내용: `claude-haiku-5-5`에 `max_tokens: 32`로 일반 1건·스트리밍 1건·캐시 없는 짧은 프롬프트. 예상 비용은 004 요금표 기준 요청 2건 합계 약 0.00005달러(입력 약 30·출력 32 토큰 가정, 0.10/0.50 단가). 확인할 것: 응답 형식이 가짜와 같은지, `usage`의 세 입력 값, 기록의 비용, 콘솔 사용량과의 일치, `anthropic-version` 보강이 통하는지.

### 10. 승인이 필요한 항목 (2026-11-05 모두 승인됨)

1. 공급처 식별을 (이름, 종류)로 바꿔 `fake`를 두 종류로 등록(1절). 기록·요금표의 `provider`는 하나로 유지된다.
2. 공통 중계 엔진 추출(2절). 코드 이동이 크지만 동작은 그대로이며 006 테스트 13개로 회귀를 확인한다.
3. 상류 인증은 `Authorization: Bearer`, `anthropic-version`은 없을 때만 `2023-06-01` 보강(3절).
4. 사용량 정규화와 스트림 병합 규칙, 끊긴 스트림은 `none`(4절).
5. 게이트웨이 오류의 Anthropic 형식 매핑과 `request_id` 필드(6절).
6. 실제 키 시험 절차(9절 2항). 실행 자체는 007이 끝난 뒤 다시 확인을 받는다.

## 확인한 공식 문서

새로 조회한 페이지는 없다. 005에서 본문을 그대로 확인한 Anthropic 네 페이지(스트리밍, Messages 레퍼런스, 오류, API 개요)와 계획 §3.2를 쓴다. 이 작업에 직접 쓰이는 사실은 다음과 같다.

- 입력 총량 = `input_tokens` + `cache_creation_input_tokens` + `cache_read_input_tokens` (Messages 레퍼런스 원문).
- `message_delta.usage`는 누적값이며 `output_tokens`는 필수, `input_tokens`·캐시 두 값은 null 허용(`MessageDeltaUsage`). 기본 예시의 `message_delta.usage`에는 `output_tokens`만 있다.
- 스트림 중 오류는 `event: error` + `{"type": "error", "error": {"type", "message"}}`로 온다. 모르는 이벤트는 그대로 처리해야 한다.
- 오류 본문은 `type: "error"`, `error{type, message}`, `request_id`. 상태 코드 → 종류 표: 400 invalid_request_error, 401 authentication_error, 402 billing_error, 403 permission_error, 404 not_found_error, 409 conflict_error, 413 request_too_large, 429 rate_limit_error, 500 api_error, 504 timeout_error, 529 overloaded_error. 502·503은 표에 없다.
- 헤더: `Authorization: Bearer`가 기본, `x-api-key`는 레거시 대체. `anthropic-version`은 개요 표에서 필수, Messages 레퍼런스에서 optional로 상충. 예시 값 `2026-10-21`(개요·Messages)과 `2023-06-01`(스트리밍·오류 curl).
- 우리가 정한 것: 모델을 찾지 못한 404에 `not_found_error`를 쓰는 것, 502·503에 `api_error`를 쓰는 것, `anthropic-version` 기본값 `2023-06-01`.

## 결정과 이유

- 어댑터 인터페이스는 여덟 메서드로 작게 잡았다. 요청에서 읽을 것, 상류 헤더에 더할 것, 일반 응답·스트림에서 사용량을 읽는 법, 오류 본문·오류 프레임의 모양이 프로토콜마다 다른 전부이고, 라우팅·타임아웃·기록·종료 판정은 같다. 엔진은 어댑터가 돌려준 값만 쓰고 프로토콜 이름을 모른다.
- `StreamObserver`도 인터페이스로 두고 OpenAI 관찰기가 그대로 구현하게 했다. OpenAI는 사용량 조각 하나를 거르고 Anthropic은 전부 통과시키는데, "프레임을 전달할지"를 관찰기가 돌려주는 구조는 둘을 같은 코드로 다룬다.
- 공급처 식별자를 (이름, 종류)로 두되 기록에는 이름만 남긴다. 요금표와 기록이 "fake"로 모이면 가짜 공급처의 비용을 두 프로토콜에서 같은 단가로 계산할 수 있고, 실제 공급처(openai, anthropic)는 이름이 애초에 다르다.
- Anthropic 스트림의 사용량은 `Accumulator` 하나로 일반 응답과 스트림을 같이 다룬다. 일반 응답은 `start`만 호출한 결과이고, 스트림은 `start` 뒤에 `delta`를 거듭 적용한 결과다. 입력·출력 중 하나라도 모르면 사용량을 아예 null로 두어 반쪽 숫자가 비용에 들어가지 않게 했다.
- 스트림 오류 이벤트에는 `request_id`를 넣지 않았다. 문서의 `error` 이벤트 예시에 없고, 클라이언트는 응답 헤더 `x-tollgate-request-id`로 같은 값을 이미 가지고 있다.

## 겪은 문제

- Docker Desktop이 다시 내려가 있어 첫 통합 테스트 실행이 전부 실패했다. 006 때와 같이 샌드박스 밖에서 띄우고 compose PostgreSQL을 다시 올렸다. 날이 바뀌면 Docker Desktop이 내려가 있는 패턴이라 작업 시작 시 확인 항목으로 둔다.
- 공급처 목록 테스트가 "Got a list of values [true,true]"로 실패했다. `fake`가 두 종류로 등록되자 JsonPath 필터 `$[?(@.name == 'fake')]`가 두 항목을 돌려줬다. 필터에 `@.type`을 더했다.
- `HttpStatus.valueOf(529)`가 "No matching constant"로 실패했다. Spring의 `HttpStatus` 열거형에 529가 없기 때문이고, 어댑터가 받는 타입인 `HttpStatusCode.valueOf(529)`로 바꿨다. 상류의 529를 그대로 전달하는 경로는 `HttpStatusCode`만 쓰므로 영향이 없다.

## 실행한 검증과 결과

| 검증 | 명령 | 결과 |
|---|---|---|
| 서버 테스트 | `./gradlew :server:test` | 114개 통과, 실패 0 (007에서 27개 추가·수정: 통합 11, 단위 15, 레지스트리 +1), 19초. 006의 OpenAI 통합 테스트 13개 수정 없이 통과 |
| 기동 | 가짜 jar + 서버 jar(`FAKE_API_KEY` 양쪽 설정) | 1초. 시작 로그에 fake가 OPENAI·ANTHROPIC 두 종류로 enabled, anthropic disabled(`ANTHROPIC_API_KEY` 비어 있음) |
| 1 일반 | `fake-small:in=120,cached=20,out=8`, `x-api-key` + `anthropic-version` 헤더 | 200, 본문 그대로(`input_tokens` 100, `cache_read_input_tokens` 20), 가짜의 `request-id` 헤더 전달. 기록 protocol anthropic, 120/20/8, `cost` 0.00011800, `response` |
| 2 스트림 | 같은 지시 + `ttft=100`, Bearer, `anthropic-version` 없음 | 14 이벤트가 그대로 전달. 기록 `stream`, 120/20/8, `cost` 0.00011800, `ttft_ms` 304(첫 스트림 요청, JIT 예열 포함) |
| 3 상류 529 | `err=529` | 529와 본문(`overloaded_error`, `request_id`) 그대로. 기록 `upstream_error`/`overloaded_error` |
| 4 끊김 | `out=5,abort=2` | 머리 3 + 내용 2 뒤 `event: error`(`api_error`, 메시지에 stream_interrupted), `message_stop` 없음. 기록 `upstream_error`/`stream_interrupted` |
| 5 키 없는 Anthropic | `claude-haiku-5-5` | 503 `api_error`, 메시지에 `ANTHROPIC_API_KEY`와 provider_disabled, `request_id`. 기록 provider anthropic |
| 6 OpenAI 경로 회귀 | `/v1/chat/completions` 같은 지시 | 200, 기록 protocol openai, `cost` 0.00011800 (정리 뒤에도 동일) |
| 7 공급처 목록 | `GET /admin/v1/providers` | 다섯 항목(fake 두 종류, ollama, openai, anthropic), 키 관련 필드 없음 |

전체 테스트 재실행 `./gradlew test --rerun-tasks`: BUILD SUCCESSFUL 36초, server 114개 + fake-provider 40개 = 154개 통과, 실패 0.

측정 조건: Windows 11 Home, JDK Temurin 25.0.5+8, Gradle 9.8.2, Docker Desktop 엔진 29.9.2, PostgreSQL 18(compose와 Testcontainers), 2026-11-05.

## 실제 Anthropic 키 시험 결과 (2026-11-05)

9절 2항의 절차대로 사용자가 자기 터미널에서 `ANTHROPIC_API_KEY`를 설정해 서버 jar를 띄우고, 가상 키로 `claude-haiku-5-5`에 일반 1건·스트리밍 1건을 직접 보냈다(`max_tokens` 32, 프롬프트 "Reply with exactly one word: pong", `anthropic-version` 헤더 없이). 키는 사용자 터미널 밖으로 나가지 않았고 서버 로그에는 `key=set`만 남는다. 사용자가 두 응답을 확인해 성공했다고 알렸고, 아래 값은 compose PostgreSQL의 `usage_records`에서 직접 읽은 것이다.

| 항목 | 일반 | 스트리밍 |
|---|---|---|
| `model_requested` / `model_served` | claude-haiku-5-5 / claude-haiku-5-5 | 같음 |
| 입력 / 캐시 / 출력 토큰 | 20 / 0 / 4 | 20 / 0 / 4 |
| `cost` | 0.00000400 | 0.00000400 |
| `usage_source` | response | stream |
| `outcome`, 상태 | ok, 200 | ok, 200 |
| `ttft_ms` | null | 845 |
| `duration_ms` | 1166 | 846 |

- 비용 검산: (20 × 0.10 + 0 × 0.01 + 4 × 0.50) ÷ 1,000,000 = 0.00000400. 요금표의 `anthropic/claude-haiku-5-5` 행(2026-11-01 확인 단가)이 `price_id`로 연결됐다. 두 건 합계 0.000008달러.
- `anthropic-version`을 보내지 않은 요청 두 건이 모두 성공했으므로, 없을 때 넣는 기본값 `2023-06-01`은 실제 API에서 통한다.
- 가짜 공급처로 검증한 정규화(세 입력 값의 합)와 스트림 병합(`message_start` 기준, `message_delta` 덮어쓰기)이 실제 응답에서도 같은 숫자를 냈다. 일반과 스트리밍의 토큰 수와 비용이 일치한다.
- 제가 직접 본 것은 DB 행이고, 응답 본문은 사용자 확인이다. 콘솔 사용량 화면과의 대조는 이 기록에 포함하지 않았다.
- 측정 조건: 서울에서 `https://api.anthropic.com` 호출, 2026-11-05 01:44 KST, 요청 2건, 동시성 1.

## 남은 문제

- `GET /v1/models`는 OpenAI 형식 하나뿐이다. Anthropic의 모델 목록 API도 같은 경로를 쓰지만 형식이 달라(`data`, `first_id`, `has_more`, `last_id`) 지금은 Anthropic 클라이언트가 그 경로를 부르면 OpenAI 형식을 받는다. 필요해지면 `Accept`나 `anthropic-version` 헤더로 구분하는 안을 검토한다.
- 캐시 쓰기 토큰(`cache_creation_input_tokens`)이 입력 단가로 계산되어 실제보다 적게 잡힌다. 004 요금표에 캐시 쓰기 단가 열을 더하는 2단계 항목과 함께 푼다.
- 끊긴 스트림의 누적 출력 토큰을 버린다. 2단계 "받은 만큼 확정" 규칙을 구현할 때 `Accumulator`의 마지막 값을 쓰면 된다.
- `RelayEngine`은 여전히 약 300줄이다. 종료 판정(`Attempt`)을 별도 파일로 떼는 정리는 008 이후 여유가 있을 때 한다.
