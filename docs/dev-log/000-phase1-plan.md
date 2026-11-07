# 000. 1단계 계획

- 작성일: 2026-11-01
- 범위: 1단계(바로 쓸 수 있는 최소 기능) 계획 수립. 이 문서를 쓰는 시점에 코드와 빌드 설정은 없다.
- 프로젝트 이름: **token-tollgate** (GitHub 동명 저장소 0개, npm·PyPI 미사용 확인, 2026-11-01). 환경 변수 접두사는 `TOLLGATE_`, 태그 헤더는 `X-Tollgate-Tag`, 가상 키 접두사는 `tt_`로 짧게 쓴다.

## 0. 이 프로젝트가 맞추려는 요구사항

이 프로젝트는 "여러 LLM 공급처의 모델을 하나의 API로 제공하는 플랫폼"을 만드는 백엔드 역할의 공개 채용 공고를 기준으로 범위를 잡았다. 공고가 명시한 기술과 업무를 `docs/PROJECT_BRIEF.md`의 단계에 대응시키면 다음과 같다.

| 공고 항목 | 대응 |
|---|---|
| Kotlin, Spring(코루틴·WebFlux), OpenAI·Anthropic 호환 API, SSE 스트리밍 | 1단계 핵심 |
| 사용량 수집·정산·과금(크레딧, 요금제, 공급 원가) | 1단계 사용량·원가 기록, 2단계 크레딧 원장·정산 점검 |
| 비용·품질·지연시간 기준 라우팅 | 3단계(비용·지연시간). "품질" 기준은 브리프에 없어 추후 제안 대상 |
| 공급처 장애 폴백, 레이트리밋, 남용 차단 | 3단계 |
| PostgreSQL / Redis / RabbitMQ | 1단계 / 3단계 / 2단계 |
| Kubernetes, ArgoCD, Prometheus·Grafana | 4단계 / 3단계 |
| 온프레미스 GPU·자체 서빙 모델 | 범위 밖. 로컬 Ollama가 자체 서빙 모델 역할을 대신 |
| AI 코딩 에이전트에게 맡기고 결과를 검증하는 개발 루프 | `CLAUDE.md`의 명세→구현→검증→dev-log 방식 |
| 사용량 대시보드 등 고객이 보는 화면 | 1단계 대시보드 |

Docker는 공고에 단어로 나오지 않지만 Kubernetes·ArgoCD가 배포하는 것이 컨테이너 이미지이고 Testcontainers도 Docker 위에서 돌므로, 1단계의 docker-compose는 4단계 k3s 배포의 바닥 역할을 한다.

알려진 틈(지금 범위는 바꾸지 않음): 공고의 "요금제"(고객 판매가)는 브리프의 "공급 원가"와 다른 개념이다. "품질" 기준 라우팅도 브리프에 없다. 둘 다 2~3단계에서 제안한다.

## 1. 개발 환경 (2026-11-01 확인, Windows 11 Home)

최초 확인 시 JDK·Node.js·Docker가 Windows와 WSL2 Ubuntu 어디에도 없었다. 같은 날 winget으로 설치하고 재부팅 후 다시 확인했다.

| 항목 | 상태 |
|---|---|
| JDK | Eclipse Temurin 25.0.5+8 LTS (`EclipseAdoptium.Temurin.25.JDK`), `JAVA_HOME` 자동 설정 |
| Node.js / npm | 26.13.0 LTS / 11.20.0 (`OpenJS.NodeJS.LTS`) |
| Docker Desktop | 4.97.0, 엔진 29.9.2(Linux, WSL2 백엔드), Compose v5.6.1. `docker run hello-world` 성공. Personal 요금제 |
| Git / gh | 2.55.0 / 2.102.0 |
| WSL2 | Ubuntu(기본), docker-desktop |
| Gradle, kotlinc | 전역 설치 없음. Gradle Wrapper 사용 |
| `git config user.email` (저장소 로컬) | GitHub noreply 주소. 조건 충족 |
| 포트 | 80, 3000, 5173, 5432, 8080, 9090, 11434 모두 비어 있음. Windows 예약 범위(50000~50059, 59144~59743)와 겹치지 않음 |

## 2. 버전 (2026-11-01 확인)

원칙: 라이브러리는 Spring Boot BOM이 관리하는 버전을 그대로 쓰고, BOM 밖(JDK, Kotlin 플러그인, Gradle, 프런트엔드)은 공식 생성기(start.spring.io, create-vite)가 내놓는 조합을 따른다. "최신"보다 "검증된 조합"을 우선한다.

| 항목 | 채택 | 최신 안정판 | 출처 | 비고 |
|---|---|---|---|---|
| JDK | **Temurin 25 LTS** (25.0.5+8) | 25.0.5+8 / 21.0.13+9 | github.com/adoptium/temurin25-binaries, temurin21-binaries | Spring Boot 4.1.2는 Java 17~27 지원. Gradle 9.8.2는 Java 25 실행 지원(9.1.0 이상). Kotlin `-jvm-target`은 26까지 |
| Kotlin | **2.3.21** | 2.4.21 (2026-10-08) | start.spring.io가 Boot 4.1.2용으로 생성한 `build.gradle.kts`; github.com/JetBrains/kotlin/releases | Boot 4.1.2는 Kotlin 2.2.x 이상 요구, BOM 관리 버전 2.3.21. 2.4.21은 Boot가 아직 관리하지 않아 보류 |
| Spring Boot | **4.1.2** (2026-10-22) | 4.1.2 | spring.io/projects/spring-boot, docs.spring.io system-requirements | Gradle 8.14+ 또는 9.x 지원 |
| Spring Framework / WebFlux | 7.0.10 (BOM) | 7.0.10 | github.com/spring-projects/spring-framework/releases | |
| kotlinx.coroutines (reactor) | 1.10.2 (BOM) | 1.11.0 (2026-05-08) | Boot 4.1.2 관리 버전; github.com/Kotlin/kotlinx.coroutines/releases | Boot가 coroutines BOM을 import |
| R2DBC PostgreSQL | 1.1.3.RELEASE (BOM) | 1.1.3.RELEASE (2026-09-21) | github.com/pgjdbc/r2dbc-postgresql/releases | |
| PostgreSQL JDBC | 42.7.14 (BOM) | | Boot 4.1.2 관리 버전 | Flyway 전용 |
| Flyway | **12.4.0** (BOM) | 13.12.0 (2026-10-30) | github.com/flyway/flyway/releases; Boot 4.1.2 관리 버전 | `flyway-core` + `flyway-database-postgresql`(Maven Central 존재 확인). 13.x는 Boot 미관리 |
| Testcontainers | 2.0.5 (BOM) | 2.0.5 (2026-04-20) | github.com/testcontainers/testcontainers-java/releases; Maven Central metadata | 2.x 아티팩트: `testcontainers-postgresql`, `testcontainers-r2dbc`, `testcontainers-junit-jupiter` |
| PostgreSQL 서버 | `postgres:18` | 18.6 (2026-08-13) | postgresql.org | 19은 RC |
| Gradle | **Wrapper 9.8.2** (2026-10-19) | 9.8.2 | gradle.org/releases; start.spring.io 생성 wrapper | Kotlin 2.3.21 플러그인의 "완전 지원" 최대는 9.3.0이지만 start.spring.io가 이 조합을 생성하므로 채택. 경고가 나오면 기록 |
| Jackson | 3.1.7 `tools.jackson` (BOM) | | Boot 4.1.2 관리 버전 | Boot 4는 Jackson 3. 패키지 `tools.jackson.*` |
| Node.js | **26.13.0 LTS** (2026-10-28) | 26.13.0 | nodejs.org previous-releases, dist/index.json | Vite 요구 20.19+ 또는 22.12+ |
| React | 19.3.1 (2026-10-14) | 19.3.1 | registry.npmjs.org; github.com/facebook/react/releases | |
| Vite / @vitejs/plugin-react | 8.3.5 / 6.1.2 | 8.3.5 / 6.1.2 | registry.npmjs.org; create-vite 9.3.3 react-ts 템플릿 | |
| TypeScript | **7.0.2** | 7.0.2 (`latest`) | registry.npmjs.org; create-vite react-ts 템플릿이 `~7.0.2` 고정 | 린터는 템플릿과 같이 **oxlint**. typescript-eslint는 TS 6.1 미만만 지원하므로 쓰지 않음 |
| Recharts | 3.10.1 | 3.10.1 | registry.npmjs.org | 차트 라이브러리 |
| vitest / @testing-library/react | 5.0.5 / 16.3.3 | 5.0.5 / 16.3.3 | registry.npmjs.org dist-tags | 대시보드 테스트 |

start.spring.io(Boot 4.1.2, Kotlin, Java 25, webflux·data-r2dbc·postgresql·flyway·testcontainers·actuator)가 생성한 의존성 구성을 작업 001의 기준으로 삼는다. 특징: Boot 4는 테스트 스타터가 모듈별로 나뉨(`spring-boot-starter-webflux-test` 등), Flyway용 `spring-jdbc`, `tools.jackson.module:jackson-module-kotlin`, 컴파일 옵션 `-Xjsr305=strict`, `-Xannotation-default-target=param-property`.

## 3. 프로토콜 확인

### 3.1 OpenAI Chat Completions

출처: 공식 OpenAPI 명세 `github.com/openai/openai-openapi`(`openapi.yaml`, info.version 2.3.0)와 developers.openai.com API 개요. platform.openai.com 렌더링 문서는 403으로 읽지 못했다.

- `POST /v1/chat/completions`, 인증 `Authorization: Bearer <key>`. 선택 헤더 `OpenAI-Organization`, `OpenAI-Project`(사용량 귀속 조직·프로젝트).
- 일반 응답: `id`, `object: "chat.completion"`, `created`, `model`, `choices[].message`, `choices[].finish_reason`(stop, length, tool_calls, content_filter, function_call), `usage`.
- `usage`: `prompt_tokens`, `completion_tokens`, `total_tokens`(필수), `prompt_tokens_details.cached_tokens`, `completion_tokens_details.reasoning_tokens` 등 선택.
- 스트리밍: `text/event-stream`. 조각은 `data: {json}` 한 줄 + 빈 줄, `object: "chat.completion.chunk"`, `choices[].delta`, `finish_reason`. 정상 종료의 마지막 프레임은 `data: [DONE]`(JSON 아님). 시작 후 실패하면 `error` 필드를 가진 JSON 프레임이 올 수 있고, 끊긴 스트림은 `[DONE]` 없이 끝날 수 있다.
- `stream_options.include_usage: true`(명세 원문 요지): `data: [DONE]` 직전에 조각 하나가 추가로 온다. 그 조각의 `usage`는 요청 전체 사용량이고 `choices`는 항상 빈 배열이다. 다른 모든 조각에도 `usage` 필드가 있지만 값은 null이다. 스트림이 끊기면 마지막 사용량 조각을 못 받을 수 있다.
- 오류 본문 `{"error": {"message", "type", "param", "code"}}`. 문서화된 상태 코드 400, 401, 403, 404, 429, 500, 503.
- `GET /v1/models`: `{"object": "list", "data": [{"id", "object": "model", "created", "owned_by"}]}`.
- 확인 필요: 429·503 응답 컴포넌트의 설명 원문, 사용량 조각의 완전한 JSON 예시.

### 3.2 Anthropic Messages

출처: platform.claude.com 공식 문서(messages, streaming, getting-started, errors, versioning, models/list). docs.claude.com은 여기로 리다이렉트된다.

- `POST /v1/messages`. 인증 표 기준 `Authorization: Bearer <key>`가 기본, `x-api-key`는 "레거시 대체, 계속 지원". `anthropic-version` 필수(getting-started 표 기준. 최신 값 2026-10-21, 예시 코드에는 2023-06-01도 쓰임). 베타는 `anthropic-beta` 헤더. 응답 요청 ID 헤더는 `request-id`.
- 일반 응답 `usage`: `input_tokens`, `output_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`, `cache_creation{...}`, `server_tool_use{...}`, `service_tier`, `speed`, `inference_geo`, `output_tokens_details.thinking_tokens`. 입력 총량은 `input_tokens + cache_creation_input_tokens + cache_read_input_tokens`, `output_tokens`가 과금 기준 출력 총량.
- `stop_reason`: end_turn, max_tokens, stop_sequence, tool_use, pause_turn, refusal, model_context_window_exceeded.
- 스트리밍: 이벤트마다 `event: <type>` 줄 + `data: {json}` 줄 + 빈 줄. `[DONE]` 없음. 순서는 `message_start` → (`content_block_start` → `content_block_delta`… → `content_block_stop`)× → `message_delta`(1개 이상) → `message_stop`. `ping`은 어디든, `error` 이벤트는 스트림 중간에 올 수 있다(예: `overloaded_error`). 모르는 이벤트는 그대로 통과시킨다.
- `message_start.message.usage`에 `input_tokens`와 초기 `output_tokens`. `message_delta.usage`는 누적값이며, 예시에 따라 `output_tokens`만 있거나, `input_tokens`·캐시·`server_tool_use`까지 있거나(웹 검색 예시, 입력 토큰이 `message_start`보다 커짐), `usage`가 아예 없다(확장 사고 예시). 파서는 `message_start` 값을 기준으로 두고 각 `message_delta.usage`에 있는 필드만 덮어쓴다.
- `content_block_delta.delta.type`: text_delta, input_json_delta, thinking_delta, signature_delta, citations_delta.
- 오류 본문 `{"type": "error", "error": {"type", "message"}, "request_id"}`. 상태 코드: 400 invalid_request_error, 401 authentication_error, 402 billing_error, 403 permission_error, 404 not_found_error, 409 conflict_error, 413 request_too_large, 429 rate_limit_error, 500 api_error, 504 timeout_error, 529 overloaded_error.
- `GET /v1/models`: `{"data": [...], "first_id", "has_more", "last_id"}`.
- 확인 필요: `message_delta.usage`에 `input_tokens`가 항상 포함되는지, `citations_delta` 형식, `anthropic-version`이 필수인지 선택인지(두 페이지가 상충).

### 3.3 게이트웨이 설계에 주는 의미

- 클라이언트 헤더(`anthropic-version`, `anthropic-beta`, `OpenAI-Organization` 등)는 그대로 상류에 전달하고, 인증 헤더만 가상 키 → 공급처 키로 바꾼다.
- OpenAI 스트림: 클라이언트가 `include_usage`를 안 켰으면 상류 요청에 켜고, 응답에서 `choices`가 빈 사용량 조각을 걸러 낸다. 켰으면 그대로 통과.
- Anthropic 스트림: 바이트는 그대로 중계하고 사용량만 곁에서 파싱한다.
- 사용량 없이 스트림이 끝나면(끊김, 오류) `usage_source = none`으로 기록하고 비용은 null. 추정치는 2단계.

## 4. 1단계 설계

### 4.1 저장소 구조

```
token-tollgate/
  settings.gradle.kts, build.gradle.kts, gradlew*   # Gradle 멀티 모듈 루트
  server/            # Kotlin + Spring Boot WebFlux 게이트웨이
  fake-provider/     # 가짜 공급처 (같은 스택, 별도 실행 파일)
  dashboard/         # React + TypeScript + Vite
  docker-compose.yml, .env.example
  scripts/smoke.sh   # curl 스모크 테스트
  docs/PROJECT_BRIEF.md, docs/dev-log/
  README.md, LICENSE, CLAUDE.md
```

가짜 공급처를 Kotlin 모듈로 두는 이유: 같은 빌드·테스트 도구를 쓰고, 서버 통합 테스트가 이 모듈의 라우터를 같은 프로세스에 띄워 실제 키 없이 돈다.

### 4.2 서버 구성 (패키지 단위)

| 구성 | 역할 |
|---|---|
| `api.openai`, `api.anthropic` | `/v1/chat/completions`, `/v1/messages`, `/v1/models` 수신. 요청 본문을 JSON으로 읽어 모델명·stream 여부 추출 |
| `auth` | `Authorization: Bearer` 또는 `x-api-key`에서 가상 키 추출 → SHA-256 해시로 조회 → 폐기 여부 확인 → 요청 컨텍스트에 키·프로젝트·`X-Tollgate-Tag` 부착. 실패는 프로토콜에 맞는 401 본문 |
| `upstream` | 공급처 설정(`tollgate.providers[]`: 이름, 종류 openai-compatible/anthropic/fake, baseUrl, 키 환경 변수 이름, 담당 모델 목록). 모델명 → 공급처 매핑, 프로토콜별 기본 공급처. `WebClient` 호출, 연결·응답 타임아웃 |
| `relay` | 일반: 상류 응답 그대로 반환 + `usage` 파싱. 스트리밍: 상류 `Flux<DataBuffer>`를 클라이언트로 흘리면서 SSE 줄 파서가 사용량·첫 토큰 시각을 읽음. OpenAI는 include_usage 주입·필터 |
| `pricing` | `model_prices` 조회(캐시) → 비용 = 입력×단가 + 출력×단가(+ 캐시 입력 단가가 있으면 별도). 단가 없으면 비용 null, `price_missing` 플래그 |
| `usage` | 요청 끝(정상·오류·끊김)마다 `usage_records` 1건 비동기 기록. 본문은 저장하지 않음 |
| `admin` | 관리 API + 관리자 토큰 검사 필터 |
| 오류 처리 | 상류 오류는 상태 코드·본문 그대로 전달. 게이트웨이 자체 오류는 호출된 엔드포인트의 프로토콜 형식 |

### 4.3 데이터 모델과 Flyway

`V1__init.sql` 하나로 시작. 시각은 모두 `timestamptz`(UTC).

| 테이블 | 주요 컬럼 |
|---|---|
| `projects` | id uuid PK, name unique, created_at, archived_at null |
| `virtual_keys` | id uuid PK, project_id FK, name, key_prefix(표시용 앞 8자), key_hash(SHA-256 hex, unique), created_at, revoked_at null, last_used_at null |
| `model_prices` | id, provider, model, input_per_mtok numeric(18,8), output_per_mtok, cached_input_per_mtok null, currency 'USD', effective_from, unique(provider, model, effective_from) |
| `usage_records` | id bigserial, request_id uuid, started_at, key_id, project_id, tag null, protocol(openai/anthropic), provider, model_requested, model_served null, input_tokens, output_tokens, cached_input_tokens, cost numeric(18,8) null, price_id null, usage_source(response/stream/none), ttft_ms null, duration_ms, http_status, streamed bool, outcome(ok/upstream_error/client_abort/timeout/gateway_error), error_type null. 인덱스 (project_id, started_at), (started_at) |

- 가상 키 평문은 발급 응답에서 한 번만 보여 주고 저장하지 않는다. 형식 `tt_` + 32자 난수(base62).
- Flyway는 JDBC로 동작한다. R2DBC만 있으면 DataSource 자동 설정이 물러나므로 `spring.flyway.url/user/password`를 따로 주고 PostgreSQL JDBC 드라이버를 둔다. Boot 4.1.2 소스의 `FlywayDataSourceCondition`이 DataSource 빈, `JdbcConnectionDetails` 빈, `spring.flyway.url` 셋 중 하나만 있어도 활성화됨을 확인했다. 테스트에서는 `@ServiceConnection`이 `PostgreSQLContainer`로부터 JDBC·R2DBC 연결 정보를 모두 만든다(Boot 문서 확인).
- 요금표 초기값은 코드에 넣지 않는다. `config/prices.example.yaml`을 두고, 시작 시 테이블이 비어 있고 `TOLLGATE_PRICES_FILE`이 지정되면 가져온다. 이후 관리 API로 수정.

### 4.4 API 목록

호환 엔드포인트
- `POST /v1/chat/completions` (일반·SSE)
- `POST /v1/messages` (일반·SSE)
- `GET /v1/models`: 요금표·공급처 설정에 있는 모델 목록을 OpenAI 형식으로 반환(도구들이 자주 호출)
- `GET /actuator/health`

관리 API (`/admin/v1`, `Authorization: Bearer <TOLLGATE_ADMIN_TOKEN>` 필요)
- 프로젝트: `GET/POST /projects`, `PATCH /projects/{id}`
- 키: `POST /projects/{id}/keys`(평문 1회 반환), `GET /keys`, `DELETE /keys/{id}`(폐기)
- 사용량: `GET /usage/summary?from&to&groupBy=day|project|model|tag&projectId&tz`, `GET /usage/records?from&to&projectId&tag&model&limit&before`
- 요금표: `GET /prices`, `PUT /prices`(upsert), `DELETE /prices/{id}`
- 공급처: `GET /providers`(이름·종류·모델만, 키 없음)

관리자 인증: 단일 관리자 토큰을 환경 변수 `TOLLGATE_ADMIN_TOKEN`으로 두고, 대시보드 로그인 화면에서 입력받아 sessionStorage에 보관, 관리 API 호출마다 Bearer로 붙인다. HTTP Basic 대신 토큰을 고른 이유: 가상 키와 같은 Bearer 방식이라 구현이 하나로 통일되고, 브라우저의 Basic 자격 증명 캐시 문제가 없으며, 환경 변수로 교체가 쉽다. 1단계에 사용자 계정·세션 테이블은 두지 않는다.

### 4.5 가짜 공급처

- 별도 Spring Boot 앱(포트 9090), `/v1/chat/completions`, `/v1/messages`. 사용량·지연은 두 층으로 제어한다.
  - 환경 변수 기본값: `FAKE_TTFT_MS`, `FAKE_CHUNK_INTERVAL_MS`, `FAKE_INPUT_TOKENS`, `FAKE_OUTPUT_TOKENS`, `FAKE_ERROR_RATE`
  - 요청별 지시를 모델명에 인코딩: `fake:in=120,out=40,ttft=200,gap=10,err=429,abort=5`. 도구가 헤더는 못 바꿔도 모델명은 바꿀 수 있어 실제 클라이언트로도 시나리오를 재현할 수 있다. `err=`는 지정 상태 코드 오류, `abort=N`은 N번째 조각 뒤 연결 끊기, `streamerr`는 스트림 중간 `error` 이벤트/프레임.
- 지시한 출력 토큰 수만큼 단어를 생성하고, 사용량 숫자는 지시값을 그대로 보고하므로 비용 계산 검증이 결정적이다.
- OpenAI 스트림: 역할 조각 → 내용 조각들 → finish 조각 → (include_usage면 사용량 조각) → `[DONE]`. Anthropic 스트림: 3.2의 이벤트 순서, `ping` 1회 삽입, `message_delta.usage`에 누적 `output_tokens`.
- 서버 통합 테스트에서는 이 모듈의 라우터를 테스트 서버에 함께 올려 `http://localhost:{port}/fake`를 상류로 쓴다.

### 4.6 대시보드

| 화면 | 데이터 |
|---|---|
| 로그인 | 관리자 토큰 입력, `/admin/v1/providers` 호출로 검증 |
| 개요 | 기간 선택(오늘/7일/30일/직접). 총 비용·요청 수·입력/출력 토큰 카드, 일별 비용 선 그래프, 프로젝트·모델·태그별 비용 막대/표 (`usage/summary`) |
| 키 관리 | 프로젝트 목록·생성, 프로젝트별 키 목록(접두사, 생성일, 마지막 사용, 상태), 발급 모달(평문 1회 표시·복사), 폐기 |
| 요청 목록 | 최근 요청 표: 시각, 프로젝트, 태그, 공급처/모델, 토큰, 비용, TTFT, 소요 시간, 상태, 스트림 여부, 오류 종류. 필터·더 보기 (`usage/records`) |
| 요금표 | 모델별 단가 표, 행 추가·수정 |

- 통화는 USD 고정. 저장은 UTC, 일별 집계는 브라우저 시간대(Asia/Seoul)를 `tz` 파라미터로 넘겨 서버가 계산한다.
- 차트 라이브러리: Recharts 3.10.1. React 컴포넌트로 선언적으로 쓰고 TypeScript 타입이 내장되어 있으며, 필요한 차트가 선·막대 두 종류뿐이라 Chart.js(캔버스, `react-chartjs-2` 래퍼 필요)의 성능 이점이 필요 없다.

### 4.7 docker-compose

| 서비스 | 이미지/빌드 | 포트 | 볼륨 | 주요 환경 변수 |
|---|---|---|---|---|
| `postgres` | `postgres:18` | 내부 5432 (호스트 노출 안 함) | `pgdata` | `POSTGRES_DB/USER/PASSWORD` |
| `server` | `./server` Dockerfile(멀티 스테이지, Temurin 25 JRE) | 8080 | 없음 | `SPRING_R2DBC_URL`, `SPRING_FLYWAY_URL`, DB 계정, `TOLLGATE_ADMIN_TOKEN`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `OLLAMA_BASE_URL`, `FAKE_PROVIDER_URL`, `TOLLGATE_PRICES_FILE` |
| `fake-provider` | `./fake-provider` Dockerfile | 9090 | 없음 | `FAKE_*` |
| `dashboard` | `./dashboard` Dockerfile(Vite 빌드 → nginx) | 3000→80 | 없음 | nginx가 `/admin/`과 `/v1/`을 `server`로 프록시 |

`.env.example` 항목: `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `TOLLGATE_ADMIN_TOKEN`, `OPENAI_API_KEY`(빈 값 허용), `ANTHROPIC_API_KEY`(빈 값 허용), `OLLAMA_BASE_URL`, `SERVER_PORT=8080`, `DASHBOARD_PORT=3000`, `FAKE_PROVIDER_PORT=9090`, `TOLLGATE_PRICES_FILE`. 실제 키가 비어 있으면 해당 공급처만 비활성화되고 가짜 공급처로 전체가 동작한다.

### 4.8 테스트 전략

- 단위(JUnit 5 + kotlin-test): 비용 계산, 가상 키 생성·해시·접두사, OpenAI SSE 줄 파서(사용량 조각·`[DONE]`·중간 오류 프레임), Anthropic 이벤트 파서(누적 usage 덮어쓰기, usage 없는 delta, 모르는 이벤트 통과), include_usage 주입·필터, 모델명 지시 파서.
- 통합(`@SpringBootTest` + Testcontainers PostgreSQL `@ServiceConnection`, 가짜 공급처 라우터 동일 프로세스): 키 발급 → 호출(일반·스트리밍 × 두 프로토콜) → `usage_records` 1건·비용 값 확인, 폐기 키 401, 상류 429 전달, 스트림 끊김 시 outcome 기록, Flyway 마이그레이션 적용.
- 스모크(`scripts/smoke.sh`): `docker compose up` 후 curl로 키 발급, 네 가지 호출, summary 조회. README의 명령과 동일하게 유지.
- 대시보드: `tsc -b`, `oxlint`, `vite build`를 기준으로 삼고, vitest + Testing Library로 포맷터·화면 단위 테스트를 둔다.
- 실제 키 없이 전체가 돈다: 통합 테스트는 가짜 공급처만 쓰고, compose도 키 없이 가짜 공급처로 동작한다. 실제 공급처 호출은 완료 기준 3의 수동 확인에서만, 사용자 확인 후.

## 5. 작업 분할 (각 1~2시간)

| # | 작업 | 목표 | 완료 기준 | 검증 |
|---|---|---|---|---|
| 001 | 프로젝트 골격 | Gradle 멀티 모듈(server, fake-provider), Kotlin 2.3.21·Spring Boot 4.1.2·Java 25 toolchain, Wrapper 9.8.2, 대시보드 Vite 골격(create-vite react-ts), `.gitignore`, LICENSE, README 뼈대 | `./gradlew build`·`npm run build` 성공, `/actuator/health` 응답, 단위 테스트 1개 통과 | 빌드 로그, curl health |
| 002 | DB 기반 | PostgreSQL + Flyway `V1__init.sql` + R2DBC 설정 + Testcontainers 통합 테스트 기반 | 통합 테스트 컨텍스트가 뜨고 마이그레이션 적용됨 | `./gradlew :server:test` |
| 003 | 가상 키와 인증 | 프로젝트·키 관리 API, 해시 저장, 인증 필터, 관리자 토큰 필터 | 발급 키로 통과, 폐기 키·없는 키 401(프로토콜별 본문) | 통합 테스트 |
| 004 | 요금표와 비용 계산 | `model_prices` API, 파일 가져오기, PricingService | 단가 유무·캐시 토큰 케이스 단위 테스트 통과 | 단위·통합 테스트 |
| 005 | 가짜 공급처 | 두 프로토콜 일반·스트리밍, 모델명 지시, 오류·끊김 시나리오 | 지시한 토큰·지연·오류가 응답에 반영 | 단위 테스트 + curl |
| 006 | OpenAI 호환 중계 | 일반·스트리밍 중계, include_usage 주입·필터, 사용량 파싱, 기록 | 네 경우(일반/스트림 × 사용량 요청 여부) 기록 정확, 상류 오류 전달 | 통합 테스트 |
| 007 | Anthropic 호환 중계 | 일반·스트리밍 중계, 이벤트 파싱, 헤더 전달, 기록 | 누적 usage·끊김·중간 오류 케이스 기록 정확 | 통합 테스트. 통과 후 사용자에게 Anthropic 실제 키 연동 시험을 제안 |
| 008 | 사용량 조회 API | summary(groupBy 4종, tz)·records(필터·커서) | 알려진 데이터로 집계 값 일치 | 통합 테스트 |
| 009 | 대시보드 1 | 로그인, 키 관리, 요금표 화면 | 발급·폐기·단가 수정이 화면에서 됨 | vitest + 수동 확인 + 타입 검사 |
| 010 | 대시보드 2 | 개요(카드·차트·분해), 요청 목록 | 기간·그룹 변경이 반영됨 | vitest + 수동 확인 + 빌드 |
| 011 | compose와 README | Dockerfile 3개, compose, `.env.example`, nginx 프록시, `smoke.sh`, README 연결 방법 | `docker compose up` 한 번으로 전체 기동, 스모크 통과 | `smoke.sh` 결과 |
| 012 | 실제 도구 연결과 마무리 | 로컬 Ollama를 base URL만 바꿔 연결, 완료 기준 1~5 대조, dev-log 정리 | 기록이 쌓이는 것을 대시보드에서 확인 | 수동 확인, 체크리스트 |

각 작업은 명세 → 구현 → 검증 → `docs/dev-log/NNN-*.md` 기록 순서로 진행하고, 다음 작업 전 승인을 받는다.

## 6. 결정 사항 (2026-11-01 확정)

| 항목 | 결정 | 근거 |
|---|---|---|
| 이름 | token-tollgate | 뜻이 바로 읽히고 GitHub·npm·PyPI에 중복 없음 |
| 라이선스 | MIT (제안, 작업 001에서 확정) | 공개 포트폴리오에 가장 흔한 선택 |
| JDK | 25 LTS | 1년 이상 지난 LTS, Boot·Gradle·Kotlin 모두 공식 지원 |
| Kotlin | 2.3.21 | Boot 4.1.2가 관리·검증하는 버전. 최신 2.4.21은 보류 |
| 포트 | 서버 8080, 대시보드 3000, 가짜 공급처 9090, PostgreSQL 내부 전용, Ollama 11434 | 이 PC에서 모두 비어 있음 |
| 1단계 실제 공급처 | 로컬 Ollama로 완료 기준 3 확인. Anthropic 실제 키는 작업 007 뒤 사용자에게 알리고 연동 시험 | 비용 0으로 먼저 확인 |
| 관리자 인증 | 단일 Bearer 토큰 | 4.4 참고 |
| 가상 키 | `Authorization: Bearer`와 `x-api-key` 둘 다 수신, 접두사 `tt_` | 두 프로토콜의 클라이언트가 모두 통과 |
| 통화·시간대 | USD, 저장 UTC, 일별 집계는 Asia/Seoul(브라우저 시간대 전달) | |
| TypeScript | 7.0.2 + oxlint | create-vite 공식 템플릿 조합 |
| 대시보드 제공 | 별도 nginx 컨테이너 | 브리프의 서비스 구성과 일치 |
| `GET /v1/models` | 포함 | 도구 호환을 위한 작은 추가 |
| 테스트 | 서버 단위·통합·스모크 + 대시보드 vitest | 꼼꼼한 테스트 요청 |

## 7. 남은 문제

- 3.1, 3.2의 "확인 필요" 항목은 해당 작업(006, 007) 구현 시 실제 응답으로 재확인하고 dev-log에 적는다.
- Kotlin 2.3.21 플러그인과 Gradle 9.8.2 조합에서 경고가 나오면 작업 001 dev-log에 기록한다.
- GitHub 원격 저장소 생성, Ollama 설치, 라이선스 파일 확정은 사용자와 확인 후 진행한다.

## 8. 변경 이력

- 2026-11-01, 작업 003: 4.2절의 `auth`(WebFilter 2개)를 Spring Security(WebFlux) 기반으로 바꿨다. 사용자가 Spring Security 사용을 택했고, 핵심 안전 장치(해시 조회, 상수 시간 토큰 비교, 키 값 비노출)는 같다. 상세는 `003-virtual-keys-auth.md`.
- 2026-11-01, 작업 004: 4.2절의 `pricing` "캐시"는 두지 않았다. 단가 조회는 요청당 한 번, 비동기 기록 경로에서 유니크 인덱스를 타는 점 조회라 측정 전에는 캐시가 상태와 갱신 버그만 더한다. 4.3절의 "테이블이 비어 있을 때만 가져오기"는 "파일에 있고 DB에 없는 (provider, model, effective_from)만 추가"로 바꿨다. 빈 테이블이면 전부 들어가므로 원래 조건을 포함하고, 모델을 추가해 재시작하면 반영되며, 관리 API로 고친 행은 덮어쓰지 않는다. `price_missing` 플래그는 컬럼 대신 `cost IS NULL AND usage_source <> 'none'`으로 파생한다. 기록은 `004-pricing.md`.
- 2026-11-02, 작업 005: 4.5절 가짜 공급처의 패키지를 `tokentollgate.fake`에서 `fakeprovider`로, 설정 파일을 `application.yml`에서 `fake-provider.yml`(`spring.config.name`)로 바꿨다. server의 컴포넌트 스캔 범위와 classpath 자원 이름 충돌을 피해 006에서 server 테스트에 올릴 수 있게 하기 위해서다. 지시에 `cached`와 `streamerr=N`, 환경 변수 `FAKE_CACHED_INPUT_TOKENS`·`FAKE_API_KEY`, 요청 `max_tokens`에 따른 출력 상한을 더했고, 응답 `model`은 지시를 뗀 기본 모델명이다. 기록은 `005-fake-provider.md`.
- 2026-11-03, 작업 006: 4.2절 `upstream`의 공급처 설정 필드를 `name`·`type`·`base-url`·`api-key-env`·`anonymous`·`default`·`models`(패턴)·타임아웃 세 가지로 정했다. 단가 조회는 응답의 `model`(`model_served`)을 먼저 쓴다. 4.5절의 "가짜 공급처 라우터를 테스트 서버에 함께 올린다"는 `@Import` + `/fake` 경로 접두사 + 테스트 보안 체인으로 구현했고, 포트는 `ApplicationContextInitializer`가 시작 전에 고정한다. Spring Boot 4.1.2의 webflux 스타터에는 `WebClient.Builder` 빈이 없어 공급처별 `WebClient`를 직접 만든다. 게이트웨이 자체 오류 코드(`model_not_found`, `provider_disabled`, `upstream_unreachable`, `upstream_timeout`, `stream_interrupted`)와 응답 헤더 `x-tollgate-request-id`를 더했다. 기록은 `006-openai-relay.md`.
- 2026-11-05, 작업 007: 공급처 식별을 (이름, 종류)로 바꿔 가짜 공급처를 `openai`·`anthropic` 두 종류로 등록했다(기록·요금표의 `provider`는 `fake` 하나). 006의 `OpenAiRelay`를 공통 `RelayEngine`과 프로토콜별 `ProtocolAdapter`(OpenAI, Anthropic)로 나눴다. Anthropic 사용량은 세 입력 값의 합을 입력 토큰으로, `cache_read_input_tokens`를 캐시 입력으로 정규화하고, 스트림은 `message_start` 기준에 `message_delta`를 덮어쓴다. 상류 인증은 `Authorization: Bearer`, `anthropic-version`은 없을 때만 `2023-06-01`을 넣는다. 기록은 `007-anthropic-relay.md`.
- 2026-11-06, 작업 008: 4.4절 사용량 조회 API의 세부를 정했다. `from`은 포함·`to`는 제외, 날짜형은 `tz`의 자정, 기본 최근 7일·최대 366일. `records`의 커서 `before`는 `id`(bigserial) 내림차순 기준이다. 집계는 비용 null을 빼고 더하며 `priceMissingRequests`·`usageUnknownRequests`를 함께 돌려준다. `model` 묶음 키는 공급처 + `COALESCE(model_served, model_requested)`이고, 계획에 없던 `provider` 필터와 `records`의 `outcome` 필터를 더했다. 조회는 `DatabaseClient`로 바인드만 쓰는 동적 SQL이다. 기록은 `008-usage-api.md`.
- 2026-11-07, 작업 009: 대시보드 뼈대(로그인, 레이아웃, API 클라이언트, 해시 라우터)와 키 관리·요금표 화면을 만들었다. 런타임 의존성은 더하지 않고 해시 라우터·`fetch` 래퍼·React 훅으로 구성했으며, 테스트는 vitest 5.0.5 + jsdom 30.1.3 + Testing Library(react 16.3.3, dom 10.4.2, jest-dom 7.0.1, user-event 14.6.8)로 `fetch`를 흉내 내어 서버 없이 돈다. 토큰은 `sessionStorage`(`tollgate.adminToken`)에만 두고 어느 호출이든 401이면 자동 로그아웃한다. 개발 중에는 Vite 프록시가 `/admin`을 8080으로 넘긴다(011의 nginx가 운영에서 같은 역할). 기록은 `009-dashboard-keys-prices.md`.
- 2026-11-07, 작업 010: 4.6절의 개요·요청 목록 화면을 만들었다. 일별 차트는 계획의 "선 그래프"를 한 계열 영역 차트 + 지표 전환(비용·요청·토큰)으로 했고 축 두 개짜리는 두지 않았다. 프로젝트·모델·태그별 분해는 계획의 "막대/표" 중 표 + CSS 비율 막대로 해 차트 라이브러리는 일별 차트에만 쓴다. Recharts 3.10.1이 첫 런타임 의존성이고(peer `react-is` 19.3.1 함께), `React.lazy`로 별도 청크(gzip 106 kB)에 둔다. 기본 화면은 개요이고 필터 상태는 해시 쿼리에 둔다. 기록은 `010-dashboard-overview-requests.md`.
- 2026-11-08, 작업 011: 4.7절의 compose를 네 서비스로 채웠다. Dockerfile 셋은 빌드 컨텍스트를 저장소 루트로 두고(`.dockerignore` 추가), JVM 둘은 `eclipse-temurin:25-jdk-alpine`에서 빌드해 `25-jre-alpine`에 Boot 계층 추출(`extract --layers --launcher`)로 올리며, 대시보드는 `node:26-alpine` 빌드 → `nginx:1.30.6-alpine`이다. 계획의 "PostgreSQL 호스트 노출 안 함"은 `bootRun` 개발 흐름을 위해 노출(`POSTGRES_PORT`)로 바꿨다. `config/`는 이미지에 복사하지 않고 읽기 전용으로 마운트해 `TOLLGATE_PRICES_FILE`의 상대 경로가 호스트와 컨테이너에서 같게 했다. nginx는 `/admin/`·`/v1/`을 프록시하고 `/v1/`은 버퍼링을 끈다. `scripts/smoke.sh`와 GitHub Actions(서버·대시보드·스모크 세 잡)를 더했다. 기록은 `011-compose-nginx-ci.md`.
