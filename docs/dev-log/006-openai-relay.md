# 006 OpenAI 호환 중계

- 명세 작성: 2026-11-03, 승인: 2026-11-03 (9절의 다섯 항목 모두), 구현·검증: 2026-11-03
- 선행: 003 가상 키 인증(`AuthenticatedKey` 주체), 004 요금표(`PricingService.cost`), 005 가짜 공급처(상류 역할)
- 후속: 007 Anthropic 호환 중계는 여기서 만든 공급처 설정·기록·중계 뼈대를 그대로 쓴다

## 목표

가상 키로 인증된 `POST /v1/chat/completions` 요청을 공급처 설정에 따라 OpenAI 호환 상류(가짜 공급처, Ollama, OpenAI)로 중계한다. 일반 응답은 그대로 돌려주고, 스트리밍은 바이트를 흘려보내면서 곁에서 사용량을 읽는다. 요청이 어떻게 끝나든(정상, 상류 오류, 끊김, 시간 초과, 클라이언트 중단) `usage_records`에 1건을 남기고 요금표로 비용을 계산한다. 도구들이 자주 부르는 `GET /v1/models`와 대시보드 로그인이 쓸 `GET /admin/v1/providers`도 만든다.

## 완료 기준

1. 일반/스트리밍 × 클라이언트의 `include_usage` 요청 여부, 네 경우 모두 가짜 공급처가 지시한 토큰 수와 요금표로 계산한 비용이 `usage_records`에 정확히 기록된다.
2. 상류 오류(상태 코드와 본문)는 그대로 전달되고 `outcome = upstream_error`로 기록된다. 스트림 중 끊김·오류 프레임·시간 초과·클라이언트 중단도 각각의 `outcome`으로 기록되며 비용은 null이다.
3. 클라이언트가 `include_usage`를 켜지 않으면 응답에 사용량 조각이 나타나지 않고, 켜면 그대로 전달된다.
4. 게이트웨이는 가상 키를 상류로 보내지 않고 공급처 키로 바꾼다. 가짜 공급처의 키 검사로 확인한다.
5. 통합 테스트가 같은 JVM에 올린 가짜 공급처만으로 돌고, 실제 키 없이 전부 통과한다.

## 명세

### 1. 공급처 설정 (`application.yml`)

```yaml
tollgate:
  upstream:
    connect-timeout: 5s
    response-timeout: 60s        # 상류 응답 헤더가 올 때까지
    stream-idle-timeout: 60s     # 스트림 프레임 사이 최대 간격
    max-in-memory-size: 16MB     # 일반 응답을 버퍼링하는 한도 (구현 중 추가, 결정과 이유 참고)
  providers:
    - name: fake
      type: openai
      base-url: ${FAKE_PROVIDER_URL:http://localhost:9090}
      api-key-env: FAKE_API_KEY
      anonymous: true
      models: ["fake-*"]
    - name: ollama
      type: openai
      base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
      anonymous: true
      default: true
    - name: openai
      type: openai
      base-url: https://api.openai.com
      api-key-env: OPENAI_API_KEY
      models: ["gpt-*", "o1*", "o3*", "o4*"]
```

| 필드 | 뜻 |
|---|---|
| `name` | 기록의 `provider` 값이자 요금표의 `provider`와 맞춰야 하는 이름 |
| `type` | 상류가 말하는 프로토콜. 006은 `openai`(OpenAI 호환 전부), 007에서 `anthropic` 추가 |
| `base-url` | 여기에 클라이언트가 부른 경로(`/v1/chat/completions`)를 그대로 붙인다 |
| `api-key-env` | 키를 읽을 환경 변수 **이름**. 값은 시작 시 `Environment`에서 읽고 어디에도 저장·출력하지 않는다 |
| `anonymous` | `true`면 키 없이도 활성(가짜, Ollama). `false`(기본)면 키가 비어 있을 때 비활성 |
| `default` | 어떤 패턴에도 맞지 않는 모델을 받는 공급처(프로토콜당 하나). 없으면 404 |
| `models` | 모델명 패턴. `*`만 와일드카드. 요청의 모델 문자열 전체와 비교하므로 `fake-small:in=120`도 `fake-*`에 맞는다 |
| 타임아웃 | 전역 기본값. 공급처 항목에 같은 이름으로 적으면 그 공급처만 덮어쓴다 |

라우팅: 설정 순서대로 첫 패턴 일치 공급처 → 없으면 `default` 공급처 → 없으면 404 `model_not_found`. 일치한 공급처가 비활성이면 503 `provider_disabled`(메시지에 환경 변수 이름). 키가 비어 있어 비활성인 공급처는 시작 로그에 "provider openai disabled: environment variable OPENAI_API_KEY is empty"로 남긴다(값은 절대 찍지 않는다).

계획 §4.2와 다른 점: 계획은 필드 이름을 정하지 않았다. `api-key-env`는 계획의 "키 환경 변수 이름"을 그대로 따른 것이고, 값이 아니라 이름을 들고 있어 설정 객체를 로그에 찍어도 키가 새지 않는다. `anonymous`·`default`·패턴은 새로 정한 것이다.

### 2. 중계 흐름

1. 보안 체인(003)이 가상 키를 확인하고 `AuthenticatedKey`(키 id, 프로젝트 id, 접두사, 태그)를 주체로 둔다. 요청 id(UUID)를 만들고 시각을 잰다.
2. 본문을 바이트로 읽어(한도 16MB) JSON 트리에서 `model`(문자열 필수, 없으면 400), `stream`, `stream_options.include_usage`만 본다. 본문은 저장하지 않는다.
3. 1절 규칙으로 공급처를 고른다.
4. 상류 요청: 같은 경로, 클라이언트 헤더 전달(4절), 공급처 키가 있으면 `Authorization: Bearer <키>`. 본문은 일반 요청이면 그대로, 스트리밍 요청이면 `stream_options.include_usage`가 `true`가 아닐 때만 `true`로 바꿔 다시 직렬화한다(계획 §3.3).
5. **일반 응답**: 상류 본문을 끝까지 받아 상태·헤더·본문을 그대로 클라이언트에 쓴다. 2xx면 본문 JSON의 `usage`(`prompt_tokens`, `completion_tokens`, `prompt_tokens_details.cached_tokens`)와 `model`을 읽어 `usage_source = response`로 기록한다. 2xx가 아니면 본문의 `error.type`을 `error_type`으로 두고 `outcome = upstream_error`, 사용량 없음.
6. **스트리밍 응답**(상류 2xx + `text/event-stream`): 상류 바이트를 SSE 프레임 단위로 나눠 클라이언트에 프레임마다 플러시한다. 프레임의 `data:` JSON을 곁에서 읽어 (a) 첫 조각의 `model`을 `model_served`로, (b) 첫 내용 조각 시각을 `ttft_ms`로, (c) `choices`가 비고 `usage`가 있는 조각을 사용량(`usage_source = stream`)으로 쓰되 클라이언트가 `include_usage`를 켜지 않았으면 그 프레임만 버리고, (d) `error` 키가 있는 프레임은 그대로 전달하고 `outcome = upstream_error`로, (e) `data: [DONE]`에서 정상 종료로 본다. 버리는 프레임 외의 바이트는 손대지 않는다. 다른 조각에 남는 `"usage": null`은 OpenAI 자체도 `include_usage` 때 보내는 형식이므로 그대로 둔다.
7. **스트림 중 상류 끊김**(`[DONE]` 없이 연결 종료)이나 **프레임 간격 시간 초과**: 클라이언트에 게이트웨이 오류 프레임 `data: {"error": {"message": ..., "type": "server_error", "param": null, "code": "stream_interrupted" | "upstream_timeout"}}`을 보내고 `[DONE]` 없이 끝낸다(OpenAI도 스트림 중 오류를 `error` 프레임으로 보낸다, 계획 §3.1). 기록은 `upstream_error`/`timeout`, 사용량 없음(OpenAI는 사용량 조각이 맨 끝이라 끊기면 못 받는다).
8. **응답 헤더 전** 실패: 연결 실패 502 `upstream_unreachable`, 응답 시간 초과 504 `upstream_timeout`, 라우팅 실패 404·503. 모두 OpenAI 오류 본문 `{"error": {"message", "type", "param", "code"}}`이며 `type`은 4xx `invalid_request_error`, 5xx `server_error`.
9. **클라이언트 중단**: 클라이언트 연결이 끊기면 상류 구독을 취소하고 `outcome = client_abort`, 그때까지의 시간과 상태로 기록한다.
10. 기록은 요청이 어떻게 끝나든 정확히 1건, 응답 경로를 막지 않도록 별도 코루틴에서 저장하고 실패는 WARN 로그로만 남긴다. 모델명을 읽기 전에 거절된 요청(400, 인증 실패 401)은 기록하지 않는다. 라우팅에 실패한 요청은 `provider = "none"`으로 기록한다.
11. 응답 헤더 `x-tollgate-request-id: <요청 id>`를 붙인다. 로그는 요청당 INFO 한 줄(요청 id, 프로젝트 id, 키 접두사, 공급처, 모델, 스트림 여부, 상태, outcome, 토큰 수, 비용, ttft, 소요 시간). 본문·헤더·키 없음.

### 3. 기록 규칙 (`usage_records`)

| 컬럼 | 값 |
|---|---|
| `protocol` | `openai` |
| `provider` | 공급처 `name`, 라우팅 실패는 `none` |
| `model_requested` / `model_served` | 요청의 `model` 문자열 / 응답(첫 조각)의 `model`, 오류면 null |
| `input_tokens`, `cached_input_tokens`, `output_tokens` | `prompt_tokens`(캐시 포함), `prompt_tokens_details.cached_tokens`(없으면 0), `completion_tokens` |
| `cost`, `price_id` | `PricingService.cost(provider, model_served ?: model_requested, started_at, usage)`. 단가 없으면 null |
| `usage_source` | `response` / `stream` / `none` |
| `ttft_ms` | 스트리밍에서 첫 내용 조각까지. 일반 응답은 null |
| `duration_ms` | 요청 수신부터 클라이언트 쓰기 완료(또는 중단·오류)까지 |
| `http_status` | 클라이언트에 보낸 상태 코드 |
| `outcome` | `ok` / `upstream_error` / `client_abort` / `timeout` / `gateway_error` |
| `error_type` | 상류 오류 본문·프레임의 `error.type`, 끊김 `stream_interrupted`, 시간 초과 `upstream_timeout`, 라우팅 `model_not_found`·`provider_disabled`, 연결 실패 `upstream_unreachable`, 그 외 null |

단가 조회에 `model_served`를 먼저 쓰는 이유: OpenAI는 별칭(`gpt-6`)을 구체 id로 바꿔 돌려주고, 가짜 공급처는 지시를 뗀 이름을 돌려준다. 요금표는 응답에 나오는 이름 기준으로 적는 것이 자연스럽다. 응답에 `model`이 없으면 요청 이름으로 찾는다.

### 4. 헤더 규칙

- 클라이언트 → 상류: 전부 전달하되 `Authorization`, `x-api-key`, `X-Tollgate-Tag`, `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Expect`, `Accept-Encoding`은 뺀다. `Accept-Encoding`을 빼는 이유는 상류가 압축해 보내면 스트림을 읽을 수 없기 때문이다. `OpenAI-Organization`·`OpenAI-Project`·`User-Agent`·`Accept`·`Content-Type`은 그대로 간다. 쿼리 문자열은 전달하지 않는다(두 API 모두 쓰지 않는다).
- 상류 → 클라이언트: 전부 전달하되 `Content-Length`, `Transfer-Encoding`, `Connection`, `Content-Encoding`은 뺀다. `x-request-id`, `openai-*`, `x-ratelimit-*`는 그대로 가므로 클라이언트가 공급처 정보를 잃지 않는다.

### 5. `GET /v1/models`와 `GET /admin/v1/providers`

- `GET /v1/models`(가상 키 필요): `{"object": "list", "data": [{"id", "object": "model", "created", "owned_by"}]}`. 목록은 활성 공급처의 요금표 모델(공급처·모델별 최신 행, `created` = `effective_from` epoch 초)과 설정의 와일드카드 없는 모델명(`created` 0)의 합집합. `owned_by`는 공급처 이름.
- `GET /admin/v1/providers`(관리자 토큰): `[{"name", "type", "baseUrl", "enabled", "default", "models"}]`. 키는 이름조차 내보내지 않는다.

### 6. 파일

server 모듈:

| 파일 | 역할 |
|---|---|
| `upstream/ProviderProperties.kt` | `tollgate.providers`, `tollgate.upstream` 바인딩 |
| `upstream/ProviderRegistry.kt` | 활성 판정, 키 해석(`Environment`), 모델 라우팅, 공급처별 `WebClient`(타임아웃·버퍼 한도 적용) |
| `relay/HeaderRules.kt` | 4절의 전달·제외 목록, `x-tollgate-request-id` |
| `relay/RelayErrors.kt` | 게이트웨이 자체 오류 본문(400·404·502·503·504, 스트림 오류 프레임) |
| `relay/openai/OpenAiRequestInspector.kt` | 본문에서 `model`·`stream`·`include_usage` 읽기, `include_usage` 주입 |
| `relay/openai/SseFrames.kt` | 바이트 → SSE 프레임 분할(버퍼 경계·`\r\n`·주석 줄·여러 `data:` 줄 처리), 원본 바이트 보존 |
| `relay/openai/OpenAiStreamObserver.kt` | 프레임 해석: 모델, 첫 내용, 사용량 조각, 오류 프레임, `[DONE]` |
| `relay/openai/OpenAiUsageParser.kt` | `usage` JSON → `TokenUsage` |
| `relay/openai/OpenAiRelay.kt` | 일반·스트리밍 중계 흐름, 종료 판정, 요청당 1회 기록 |
| `relay/openai/OpenAiChatController.kt` | `POST /v1/chat/completions` |
| `relay/openai/ModelsController.kt` | `GET /v1/models` |
| `usage/UsageRecorder.kt` | 기록 조립, 비용 계산, 비동기 저장, 요청당 로그 한 줄 |
| `admin/ProviderController.kt`, `AdminDtos.kt` | 공급처 목록 응답 |
| `db/Repositories.kt` | `UsageRecordRepository.findByRequestId` 추가 |
| `application.yml` | 1절 설정, `spring.http.codecs.max-in-memory-size: 16MB` |
| `build.gradle.kts` | `testImplementation(project(":fake-provider"))` |
| `.env.example`, `README.md` | `FAKE_PROVIDER_URL`, `OLLAMA_BASE_URL`, `OPENAI_API_KEY`(빈 값), "중계와 공급처 설정" 절 |
| `docs/dev-log/000-phase1-plan.md` §8 | 변경 이력 |

fake-provider 모듈(테스트 탑재를 위한 작은 변경 2건):

| 파일 | 변경 |
|---|---|
| `error/FakeErrorHandler.kt` | `@RestControllerAdvice(basePackages = ["fakeprovider"])`로 한정. 게이트웨이 컨텍스트에 올렸을 때 게이트웨이 컨트롤러의 400까지 가로채지 않게 |
| `auth/ApiKeyFilter.kt`, `FakeProperties.kt` | `fake.path-prefix`(기본 빈 값) 추가. 검사 대상 경로를 `<prefix>/v1/`로 계산해 `/fake` 아래에 올렸을 때 게이트웨이의 `/v1/**`를 건드리지 않게 |

새 의존성: 없음. Reactor Netty 클라이언트는 webflux 스타터에 들어 있고, `WebClient`는 직접 만든다(겪은 문제 참고).

### 7. 테스트

통합 테스트의 상류: `FakeUpstreamConfiguration`(`@TestConfiguration`)이 가짜 공급처의 컨트롤러 두 개·예외 처리·파서·응답 작성기·키 필터·`FakeProperties`를 `@Import`로 등록하고, `WebFluxConfigurer.configurePathMatching(addPathPrefix("/fake", HandlerTypePredicate.forBasePackage("fakeprovider")))`로 경로를 옮기며, `@Order(0)` 보안 체인이 `/fake/**`를 `permitAll`한다. 포트는 `FakeUpstreamInitializer`(`ApplicationContextInitializer`, `@IntegrationTest`의 `@ContextConfiguration(initializers)`)가 컨텍스트 시작 전에 빈 포트를 하나 잡아 `server.port`와 공급처 `base-url`(`http://localhost:<포트>/fake`)에 함께 넣는다(`@IntegrationTest`는 `DEFINED_PORT`). 테스트용 공급처 목록은 이 초기화기가 통째로 정의한다(Spring Boot는 목록 속성을 소스 간에 합치지 않고 우선순위 높은 소스가 통째로 이긴다). 목록: `fake`(`FAKE_API_KEY` 테스트 속성 = 가짜의 `fake.api-key`), `fake-nokey`(키 없음, `nokey-*`), `fake-slow`(`response-timeout: 500ms`, `slow-*`), `openai`(절대 설정되지 않는 변수 이름 → 비활성).

| 파일 | 내용 |
|---|---|
| `relay/openai/OpenAiRelayTest` (통합, 13) | 1 일반·`include_usage` 없음: 200, 본문 그대로, `x-tollgate-request-id`, 기록(120/20/8, `cost` 0.00011800 = (100×1.00 + 20×0.10 + 8×2.00)/1,000,000, `price_id`, `response`, `ok`, `ttft` null, 태그). 2 일반·`include_usage` 있음: 같은 기록. 3 스트림·없음: 11프레임, 사용량 조각 없음, `[DONE]` 있음, 기록 `stream`, `ttft_ms` ≥ 100. 4 스트림·있음: 7프레임 중 사용량 조각 전달. 5 상류 429: 상태·본문 그대로, `upstream_error`/`rate_limit_error`, 비용 null. 6 `abort=2`: 내용 2개 + 게이트웨이 오류 프레임(`stream_interrupted`), `[DONE]` 없음, 클라이언트는 정상 종료로 봄. 7 `streamerr=2`: 오류 프레임 그대로, `error_type` server_error. 8 `slow-model:ttft=2000` → 504 `upstream_timeout`, 기록 `timeout`. 9 `unknown-model` → 404 `model_not_found`, provider `none`. 10 `gpt-6-astra` → 503 `provider_disabled`(메시지에 변수 이름). 11 `nokey-model` → 가짜의 401 그대로, `upstream_error`/`authentication_error`. 12 클라이언트 중단(프레임 2개 뒤 취소) → `client_abort`. 13 `model` 없는 본문 → 400, 기록 없음 |
| `relay/openai/ModelsApiTest` (통합, 2) | 요금표의 `fake-relay`·`fake-large`가 `owned_by: fake`로 나옴. 키 없이 401 |
| `admin/ProviderAdminApiTest` (통합, 2) | 목록·`enabled` 값, 응답 본문에 키 값도 변수 이름도 없음. 토큰 없이 401 |
| `relay/openai/SseFramesTest` (단위, 5) | 프레임 경계와 바이트 보존, 버퍼 경계에서 잘린 프레임, `\r\n`과 두 번째 CR 직전 분할, 주석 프레임·여러 `data:` 줄, 종료 없는 잔여 바이트 |
| `relay/openai/OpenAiStreamObserverTest` (단위, 4) | 사용량 조각 읽기·버리기/전달, 오류 프레임, JSON 아닌 프레임 통과, tool_calls도 첫 내용 |
| `relay/openai/OpenAiRequestInspectorTest` (단위, 4) | 일반 요청 바이트 그대로, 주입과 다른 필드 보존, 기존 `stream_options` 확장·`true`면 그대로, `model` 없음·잘못된 JSON·배열·빈 본문 거절 |
| `upstream/ProviderRegistryTest` (단위, 5) | 패턴 일치(지시 포함), 기본 공급처 없으면 NotFound, 비활성, 키 읽기·슬래시 제거·타임아웃 덮어쓰기·`toString`에 키 없음, 중복 이름 거절 |

통합 테스트의 기록 확인은 `UsageRecordRepository.findByRequestId`를 50ms 간격으로 폴링한다(비동기 저장). 스트림 검증은 005와 같은 원시 바이트 읽기(`RelayTestSupport.readSse`)로 하고, 테스트 전용 단가 모델 `fake-relay`를 쓴다(겪은 문제 참고).

### 8. 수동 확인

compose PostgreSQL + 가짜 공급처 jar(9090, `FAKE_API_KEY` 설정) + 서버 jar(`FAKE_API_KEY` 같은 값, `TOLLGATE_PRICES_FILE`): 관리 API로 프로젝트·키 발급 → curl 아홉 경우 → `psql`로 `usage_records` 확인. 결과는 "실행한 검증과 결과"에 있다.

### 9. 승인이 필요한 항목 (2026-11-03 모두 승인됨)

1. 공급처 설정 형식(1절): `api-key-env`, `anonymous`, `default`, `models` 패턴, 전역·공급처별 타임아웃 세 가지.
2. 단가 조회 모델: `model_served` 우선, 없으면 `model_requested`(3절).
3. 가짜 공급처를 server 테스트에 올리는 방식(7절)과 그를 위한 fake-provider 변경 2건.
4. 게이트웨이 자체 오류와 기록 규칙(2·3절): 404 `model_not_found`, 503 `provider_disabled`, 502 `upstream_unreachable`, 504 `upstream_timeout`, 스트림 중 끊김·시간 초과는 오류 프레임 `stream_interrupted`·`upstream_timeout`; 모델명을 읽기 전 거절은 기록하지 않고 라우팅 실패는 `provider = "none"`으로 기록; 응답 헤더 `x-tollgate-request-id`.
5. 요청·일반 응답 본문의 메모리 한도 16MB(`spring.http.codecs.max-in-memory-size`).

## 확인한 사실 (2026-11-03)

- OpenAI Chat Completions의 응답·조각·`include_usage`·오류 형식은 005에서 공식 페이지로 확인한 내용(`005-fake-provider.md`의 "확인한 공식 문서")과 계획 §3.1을 그대로 쓴다. 새로 조회한 페이지는 없다.
- 로컬 Gradle 캐시의 jar로 확인: Spring Boot 4.1.2의 코덱 메모리 한도 속성은 `spring.http.codecs.max-in-memory-size`(설정 메타데이터), Spring WebFlux 7.0.10에 `PathMatchConfigurer.addPathPrefix(String, Predicate<Class<?>>)`와 `HandlerTypePredicate.forBasePackage(String...)`, Spring Test 7.0.10에 `DynamicPropertyRegistrar`·`DynamicPropertyRegistry`, Boot 테스트의 `WebEnvironment.DEFINED_PORT`가 있다.
- 구현 중 확인한 것: Reactor Netty 클라이언트의 응답 시간 초과는 `HttpClient.responseTimeout`, 연결 시간 초과는 `ChannelOption.CONNECT_TIMEOUT_MILLIS`로 두며 초과 시 `WebClientRequestException`의 원인 사슬에 `ReadTimeoutException`이 있다(테스트 8로 확인). 상류 연결이 끊기면 `WebClient`의 본문 `Flux`가 오류로 끝난다(테스트 6과 수동 확인 6으로 확인).

## 결정과 이유

- 포트 고정은 명세의 `DynamicPropertyRegistrar` 빈 대신 `ApplicationContextInitializer`로 했다. `server.port`는 내장 서버가 만들어질 때(컨텍스트 `onRefresh`) 읽히는데, 빈 기반 등록은 그보다 늦을 수 있어 확실한 쪽을 택했다. 초기화기는 환경이 준비되는 시점에 실행되므로 시점 문제가 없다.
- `WebClient`는 공급처마다 직접 만든다. Spring Boot 4.1.2의 webflux 스타터는 서버 쪽만 자동 설정하고 `WebClient.Builder` 빈을 주지 않는다(겪은 문제). 클라이언트 모듈 의존성을 더하는 대신 `WebClient.builder()`에 Reactor Netty 커넥터와 코덱 한도를 직접 넣었고, 그래서 일반 응답 버퍼 한도가 `tollgate.upstream.max-in-memory-size`로 따로 생겼다. 서버 쪽 요청 본문 한도는 명세대로 `spring.http.codecs.max-in-memory-size`다.
- 스트림 프레임 분할기는 바이트 배열을 직접 훑는다. Spring의 SSE 디코더를 쓰면 `data: ` 접두사와 빈 줄이 재구성되어 상류 바이트와 달라지고 `[DONE]`도 데이터로만 남는다. 프레임의 원본 바이트를 그대로 보존해야 "걸러 낸 사용량 조각 하나 외에는 손대지 않는다"를 지킬 수 있다.
- 스트림 중 상류 실패는 `onErrorResume`으로 오류 프레임을 하나 더 내보내고 스트림을 정상 종료한다. 클라이언트 입장에서는 `[DONE]` 없이 `error` 프레임으로 끝나는, OpenAI가 문서화한 형태다. 연결을 그냥 끊으면 클라이언트가 원인을 알 수 없다.
- 기록은 `Attempt`가 `AtomicBoolean`으로 정확히 한 번만 넘기고, `UsageRecorder`가 자체 `CoroutineScope`에서 저장한다. 요청 코루틴이 취소되어도(클라이언트 중단) 저장은 별도 스코프라 끝까지 간다. 비용 계산도 저장 쪽에서 하므로 응답 경로에 DB 조회가 없다.
- `ttft_ms`는 게이트웨이가 상류에서 첫 내용 조각을 받은 시각이다. 역할 조각(`content: ""`)은 세지 않고, `tool_calls` 조각은 내용으로 본다.
- 모델명을 읽기 전에 거절된 400은 기록하지 않는다. 기록 테이블의 `model_requested`가 NOT NULL이고, 귀속할 모델이 없는 요청까지 적으면 집계만 흐려진다.

## 겪은 문제

- 첫 통합 테스트 실행이 전부 "Could not find a valid Docker environment"로 실패했다. 몇 시간 전에는 돌던 Docker Desktop이 내려가 있었다. 005 때와 같이 샌드박스 밖에서 다시 띄우자 3초 만에 엔진이 올라왔고, compose의 PostgreSQL 컨테이너는 멈춰 있어 수동 확인 전에 `docker compose up -d postgres`로 다시 올렸다.
- Docker가 올라온 뒤에도 컨텍스트 로딩이 실패했다. 원인은 `ProviderRegistry`가 주입받으려던 `WebClient.Builder` 빈이 없는 것이었다. 결정과 이유대로 빌더를 직접 만들어 해결했다.
- 비용 검증 세 건이 0.00002280으로 어긋났다. 통합 테스트가 한 DB를 공유하는데 `RepositoryTest`가 `fake/fake-small`에 다른 단가 행을 넣어 그 행이 잡혔다. 중계 테스트는 전용 모델 `fake-relay`(단가 1.00/2.00/0.10)를 쓰도록 바꿨다.
- `ModelsApiTest`의 테스트 하나가 조용히 실행되지 않았다. `= runBlocking { ... }`의 마지막 식이 WebTestClient 스펙이라 메서드 반환형이 Unit이 아니었고, JUnit 5는 값을 돌려주는 `@Test` 메서드를 건너뛴다. `runBlocking<Unit>`으로 고치자 실행됐고, 이번에는 JsonPath 필터식(`$.data[?(@.id == ...)].created`)의 결과가 목록이라 `value<Int>` 캐스트가 깨져 목록으로 받도록 바꿨다. 집계 수(86)가 기대(87)와 달라 발견했다.

## 실행한 검증과 결과

| 검증 | 명령 | 결과 |
|---|---|---|
| 서버 테스트 | `./gradlew :server:test` | 87개 통과, 실패 0 (006에서 35개 추가), 16초 |
| 가짜 공급처 테스트 | `./gradlew :fake-provider:test` | 40개 통과 (필터·예외 처리 변경 뒤에도 그대로) |
| 기동 | 가짜 jar + 서버 jar(`FAKE_API_KEY` 양쪽 설정) | 서버 2초, 가짜 1초. 시작 로그: fake enabled key=set, ollama enabled key=none, openai disabled(`OPENAI_API_KEY` 비어 있음), 요금표 파일 11행 이미 존재 |
| 1 일반 | `fake-small:in=120,cached=20,out=8`, `X-Tollgate-Tag` | 200, 본문 그대로, `x-tollgate-request-id` 헤더. 기록 120/20/8, `cost` 0.00011800(예시 요금표 1.00/0.10/2.00), `response`, `ok` |
| 2 일반 + `include_usage` | 같은 지시 | 200, 기록 동일 |
| 3 스트림 | 같은 지시 + `ttft=100` | 역할 → 내용 8 → 종료 → `[DONE]`, 사용량 조각 없음. 기록 `stream`, `cost` 0.00011800, `ttft_ms` 161 |
| 4 스트림 + `include_usage` | `out=3` | 사용량 조각이 `[DONE]` 앞에 전달. 기록 `stream`, `cost` 0.00010800 |
| 5 상류 429 | `err=429` | 429와 본문 그대로. 기록 `upstream_error`/`rate_limit_error`, 비용 null |
| 6 끊김 | `out=5,abort=2` 스트림 | 내용 2개 뒤 게이트웨이 프레임 `stream_interrupted`, `[DONE]` 없음, HTTP 200. 기록 `upstream_error`/`stream_interrupted` |
| 7 비활성 공급처 | `gpt-6-astra` | 503 `provider_disabled`, 메시지에 `OPENAI_API_KEY`. 기록 provider openai, `gateway_error` |
| 8 `GET /v1/models` | 가상 키 | `fake-large`, `fake-small`(`owned_by` fake, `created` 1767225600 = 2026-01-01) |
| 9 `GET /admin/v1/providers` | 관리자 토큰 | 세 공급처의 이름·종류·주소·활성·기본·패턴. 키 관련 필드 없음 |
| 로그 | 요청 7건 | 요청당 한 줄, 본문·헤더·키 없음. 첫 요청 256ms(JIT 예열), 이후 일반 4~6ms |

전체 테스트 재실행 `./gradlew test --rerun-tasks`: BUILD SUCCESSFUL 33초, server 87개 + fake-provider 40개 = 127개 통과, 실패 0.

측정 조건: Windows 11 Home, JDK Temurin 25.0.5+8, Gradle 9.8.2, Docker Desktop 엔진 29.9.2, PostgreSQL 18(compose와 Testcontainers), 2026-11-03.

## 남은 문제

- 007에서 `type: anthropic` 공급처와 `/v1/messages` 중계를 더할 때, 가짜 공급처 하나를 두 종류로 등록하면 이름이 둘이 되어 요금표의 `provider`와 어긋난다. 요금표용 이름 별칭을 둘지 그때 정한다.
- 스트림 조각에 남는 `"usage": null`은 OpenAI의 `include_usage` 형식과 같지만, 클라이언트가 옵션을 켜지 않았는데 보이는 키라는 점은 남는다. 문제가 되는 클라이언트가 나타나면 조각을 다시 직렬화해 지우는 방식을 검토한다.
- 클라이언트 중단 테스트는 프레임 2개 뒤 취소를 10초 안에 기록으로 확인한다. 느린 환경에서 흔들릴 수 있으니 CI(011)에서 지켜본다.
- 쿼리 문자열은 상류에 전달하지 않는다. 필요한 OpenAI 호환 서버가 나타나면 더한다.
- 003의 "모르는 `/v1` 경로는 Boot 기본 404 JSON"은 그대로다. 007 뒤에 프로토콜 형식으로 바꿀지 정한다.
