# token-tollgate

AI 도구와 에이전트가 쓰는 LLM 호출을 한곳으로 모아, 작업별 비용을 보고, 예산을 넘지 않게 막고, 공급처 장애에도 멈추지 않게 하는 개인·소규모 팀용 LLM 게이트웨이.

기존 도구는 base URL과 키만 바꿔 연결한다. OpenAI 호환 `POST /v1/chat/completions`와 Anthropic 호환 `POST /v1/messages`(일반·SSE 스트리밍)를 제공하고, 지나가는 모든 호출의 토큰·비용·지연시간을 기록한다.

설계 기준은 [docs/PROJECT_BRIEF.md](docs/PROJECT_BRIEF.md), 진행 기록은 [docs/dev-log/](docs/dev-log/)에 있다.

## 상태

1단계(바로 쓸 수 있는 최소 기능) 진행 중. 계획은 [docs/dev-log/000-phase1-plan.md](docs/dev-log/000-phase1-plan.md).

## 구성

| 디렉터리 | 내용 |
|---|---|
| `server/` | Kotlin + Spring Boot WebFlux 게이트웨이 |
| `fake-provider/` | 지연·오류·토큰 사용량을 설정으로 흉내 내는 시험용 공급처 |
| `dashboard/` | React + TypeScript + Vite 대시보드 |
| `config/` | 예시 요금표 등 실행 시 읽는 설정 파일 |

## 빌드와 테스트

요구 사항: JDK 25, Node.js 26, Docker(통합 테스트와 로컬 PostgreSQL용).

```bash
./gradlew build
```

```bash
cd dashboard && npm install && npm run build
```

## 로컬 실행

`.env.example`을 `.env`로 복사하고 값을 채운다. `.env`는 docker compose가 읽는다. `bootRun`으로 서버를 직접 띄울 때는 Gradle이 `.env`를 읽지 않으므로 같은 값을 셸 환경 변수로 준다. `TOLLGATE_ADMIN_TOKEN`은 16자 이상이어야 하며 없으면 서버가 기동하지 않는다.

```bash
docker compose up -d postgres
```

```bash
TOLLGATE_ADMIN_TOKEN=<16자 이상 토큰> TOLLGATE_PRICES_FILE=config/prices.example.yaml ./gradlew :server:bootRun
```

PowerShell에서는 `$env:TOLLGATE_ADMIN_TOKEN = "<토큰>"`처럼 먼저 지정한 뒤 `./gradlew :server:bootRun`을 실행한다. `bootRun`의 작업 디렉터리는 저장소 루트이므로 `config/...` 같은 상대 경로를 그대로 쓸 수 있다.

서버는 8080 포트에서 뜬다. 관리 API는 `/admin/v1` 아래에 있고 `Authorization: Bearer <TOLLGATE_ADMIN_TOKEN>`이 필요하다. 가상 키는 `POST /admin/v1/projects/{id}/keys`로 발급하며, 평문은 그 응답에만 한 번 나온다. 클라이언트는 발급받은 키를 `Authorization: Bearer <키>` 또는 `x-api-key: <키>`로 보낸다.

## 중계와 공급처 설정

도구의 base URL을 `http://localhost:8080/v1`로, API 키를 발급받은 가상 키로 바꾸면 OpenAI 형식 `POST /v1/chat/completions`와 Anthropic 형식 `POST /v1/messages`(둘 다 일반·SSE 스트리밍)가 공급처로 중계된다. 요청마다 `usage_records`에 프로토콜·공급처·모델·입력/출력/캐시 토큰·비용·첫 토큰까지 시간·소요 시간·상태·결과가 1건 기록되고, 응답 헤더 `x-tollgate-request-id`가 그 기록의 `request_id`다. 요청·응답 본문은 저장하지 않는다. 선택 헤더 `X-Tollgate-Tag: <작업 이름>`을 보내면 기록에 태그가 남는다.

```bash
curl -s http://localhost:8080/v1/chat/completions -H 'Authorization: Bearer <가상 키>' -H 'Content-Type: application/json' -d '{"model":"fake-small:in=120,cached=20,out=8","messages":[{"role":"user","content":"hi"}]}'
```

```bash
curl -s http://localhost:8080/v1/messages -H 'x-api-key: <가상 키>' -H 'anthropic-version: 2023-06-01' -H 'Content-Type: application/json' -d '{"model":"fake-small:in=120,cached=20,out=8","max_tokens":64,"messages":[{"role":"user","content":"hi"}]}'
```

OpenAI 형식의 스트리밍 요청은 상류에 `stream_options.include_usage`를 켜서 사용량 조각을 읽고, 클라이언트가 그 옵션을 켜지 않았으면 그 조각만 걸러 낸 뒤 나머지 바이트를 그대로 흘려보낸다. Anthropic 형식은 `message_start`와 `message_delta`에 사용량이 들어 있어 본문도 스트림도 손대지 않고 그대로 전달하며, 세 입력 값(`input_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`)의 합을 입력 토큰으로, `cache_read_input_tokens`를 캐시 입력 토큰으로 기록한다. 그래서 같은 사용량이면 두 형식의 비용이 같다. `anthropic-version` 헤더는 클라이언트 값을 그대로 전달하고 없으면 `2023-06-01`을 넣는다.

상류 오류는 상태 코드와 본문 그대로 전달한다. 상류 스트림이 끊기거나 멈추면 OpenAI 형식은 `data: {"error": {..., "code": "stream_interrupted" | "upstream_timeout"}}` 프레임을, Anthropic 형식은 `event: error` 이벤트를 보내고 종료 프레임 없이 닫는다.

공급처는 `server/src/main/resources/application.yml`의 `tollgate.providers` 목록이다. 같은 이름을 종류별로 한 번씩 적을 수 있다(가짜 공급처는 두 프로토콜을 모두 말한다).

| 필드 | 뜻 |
|---|---|
| `name` | 기록의 `provider`이자 요금표의 `provider`와 맞출 이름 |
| `type` | 상류 프로토콜. `openai`(OpenAI 호환 전부) 또는 `anthropic` |
| `base-url` | 여기에 `/v1/chat/completions` 또는 `/v1/messages`를 붙여 호출한다 |
| `api-key-env` | 키를 읽을 환경 변수 **이름**. 값은 어디에도 적지 않는다 |
| `anonymous` | `true`면 키 없이 활성(가짜 공급처, Ollama) |
| `default` | 같은 종류에서 어떤 패턴에도 맞지 않는 모델을 받는 공급처 |
| `models` | 모델명 패턴(`*` 와일드카드). 설정 순서대로 첫 일치가 이긴다 |

기본 설정은 `fake-*` → 가짜 공급처(`FAKE_PROVIDER_URL`, 두 종류), `gpt-*`·`o1*`·`o3*`·`o4*` → OpenAI(`OPENAI_API_KEY`), `claude-*`와 그 외 Anthropic 형식 요청 → Anthropic(`ANTHROPIC_API_KEY`), 그 외 OpenAI 형식 요청 → Ollama(`OLLAMA_BASE_URL`)다. 키가 비어 있는 공급처로 가는 요청은 503 `provider_disabled`로 거절되며 메시지에 변수 이름이 나온다. 타임아웃은 `tollgate.upstream`(연결 5초, 응답 헤더 60초, 스트림 프레임 간격 60초)이며 공급처 항목에서 덮어쓸 수 있다.

`GET /v1/models`(가상 키 필요)는 활성 공급처의 요금표 모델을 OpenAI 형식으로 돌려주고, `GET /admin/v1/providers`(관리자 토큰)는 공급처 목록을 키 없이 보여 준다.

## 요금표

모델별 단가는 `model_prices` 테이블에 있고 코드에는 없다. 단위는 100만 토큰당 USD이며 입력·출력·캐시 입력(선택) 세 단가를 둔다. `TOLLGATE_PRICES_FILE`에 YAML 파일을 지정하면 시작할 때 파일에 있고 DB에 없는 (provider, model, effective_from) 행만 추가한다. 형식과 예시는 [config/prices.example.yaml](config/prices.example.yaml)에 있고, 값은 각 공급처 공식 가격 페이지를 확인한 날짜와 함께 적혀 있다.

요청 하나의 비용은 다음과 같이 계산한다(토큰 수 × 단가 ÷ 1,000,000, 소수 8자리 반올림).

```
비용 = (입력 토큰 − 캐시 입력 토큰) × 입력 단가
     + 캐시 입력 토큰 × 캐시 입력 단가(없으면 입력 단가)
     + 출력 토큰 × 출력 단가
```

입력 토큰은 캐시에서 읽은 부분을 포함한 전체 프롬프트 토큰이고, 캐시 입력 토큰은 그중 캐시에서 읽은 부분이다. 단가가 없는 모델의 요청은 비용 없이(`cost = null`) 기록된다. 단가가 바뀌면 행을 고치지 않고 `effective_from`이 더 늦은 행을 추가한다. 요청 시각 기준으로 가장 최근 행이 적용된다.

관리 API: `GET /admin/v1/prices?provider&model`, `PUT /admin/v1/prices`(같은 provider·model·effectiveFrom이 없으면 201, 있으면 단가를 바꾸고 200), `DELETE /admin/v1/prices/{id}`(사용 기록이 참조하면 409).

## 사용량 조회

기록된 요청은 관리 API 두 개로 본다. 둘 다 `Authorization: Bearer <TOLLGATE_ADMIN_TOKEN>`이 필요하고, 두 프로토콜의 기록을 함께 다룬다.

- `GET /admin/v1/usage/summary`: 기간 안의 기록을 `groupBy`(`day`, `project`, `model`, `tag`)로 묶어 요청 수, 성공 수, 입력·캐시·출력 토큰 합, 비용 합, 단가가 없어 비용을 못 계산한 요청 수, 사용량 자체를 모르는 요청 수를 돌려준다. `totals`는 기간 전체, `groups`는 묶음별이다.
- `GET /admin/v1/usage/records`: 최근 요청 목록. `limit`(기본 50, 최대 200)과 커서 `before`(응답의 `nextBefore`를 그대로 넘김)로 넘겨 본다. 프로젝트 이름과 키 접두사가 함께 나오고 키 평문은 나오지 않는다.

공통 파라미터는 `from`(포함)·`to`(제외)·`tz`·`projectId`·`tag`·`model`·`provider`이고, `records`에는 `outcome` 필터가 더 있다. `from`과 `to`는 `2026-11-04T15:00:00Z` 같은 instant나 `2026-11-05` 같은 날짜이며, 날짜는 `tz`의 자정으로 해석한다. 기본은 최근 7일, 최대 366일이다. 기록은 UTC로 저장되고 `day` 묶음만 `tz`로 계산하므로, 같은 기록이 `tz=UTC`에서는 11월 4일, `tz=Asia/Seoul`에서는 11월 5일로 묶일 수 있다. 비용은 소수 8자리 숫자이고 `null`이 아닌 값만 더한다.

```bash
curl -s 'http://localhost:8080/admin/v1/usage/summary?from=2026-11-01&to=2026-11-08&groupBy=model&tz=Asia/Seoul' -H 'Authorization: Bearer <관리자 토큰>'
```

## 대시보드

`dashboard/`는 관리 API 위에 올라가는 React 화면이다. 관리자 토큰으로 로그인해 프로젝트와 가상 키를 관리하고 요금표를 고친다. 개요와 요청 목록 화면은 다음 작업(010)에서 더해진다.

```bash
cd dashboard && npm install && npm run dev
```

개발 서버는 5173 포트에서 뜨고 `/admin` 요청을 `http://localhost:8080`으로 프록시하므로 서버를 먼저 띄운다. 처음 열면 로그인 화면이 나오고 서버에 준 `TOLLGATE_ADMIN_TOKEN` 값을 넣는다. 토큰은 그 탭의 `sessionStorage`에만 있어 탭을 닫으면 사라지고, 서버가 401을 돌려주면 자동으로 로그아웃된다.

- 키 관리(`#/keys`): 프로젝트 생성·이름 변경·보관, 프로젝트별 키 목록(접두사, 생성 시각, 마지막 사용, 상태), 키 발급(평문은 발급 직후 한 번만 보이고 복사 버튼이 있다), 폐기.
- 요금표(`#/prices`): 공급처·모델별 최신 단가와 이력, 단가 추가(같은 공급처·모델·적용 시작으로 저장하면 그 행의 단가를 교체한다), 삭제(사용 기록이 참조하는 행은 서버가 거절한다).

검사와 빌드는 다음과 같다. 테스트는 `fetch`를 흉내 내므로 서버 없이 돈다. 빌드 산출물은 `dashboard/dist`에 생기며 배포 구성(011)에서 nginx가 서빙한다.

```bash
cd dashboard && npm run lint && npm test && npm run build
```

## 가짜 공급처

`fake-provider/`는 실제 키 없이 OpenAI Chat Completions(`POST /v1/chat/completions`)와 Anthropic Messages(`POST /v1/messages`)의 일반·스트리밍 응답, 오류, 스트림 끊김을 흉내 내는 별도 서버다. 통합 테스트, compose, 부하 시험의 상류로 쓴다. 응답 형식은 두 공급처의 공식 문서 기준이며 확인한 내용은 [docs/dev-log/005-fake-provider.md](docs/dev-log/005-fake-provider.md)에 있다.

```bash
./gradlew :fake-provider:bootRun
```

9090 포트에서 뜬다(`FAKE_PROVIDER_PORT`). 인증은 기본적으로 검사하지 않고, `FAKE_API_KEY`를 주면 그 값을 `Authorization: Bearer` 또는 `x-api-key`로 보내야 한다.

사용량·지연·오류는 요청의 모델명에 지시로 적는다. 형식은 `<모델명>:<키>=<값>,<키>=<값>`이고, 응답의 `model`에는 지시를 뗀 모델명만 들어간다(요금표의 모델명과 맞추기 위해서다).

| 키 | 뜻 | 없을 때 기본값 |
|---|---|---|
| `in` | 입력 토큰 총량(캐시에서 읽은 부분 포함) | `FAKE_INPUT_TOKENS` (100) |
| `cached` | 그중 캐시에서 읽은 토큰 | `FAKE_CACHED_INPUT_TOKENS` (0) |
| `out` | 출력 토큰 = 생성 단어 수 = 스트림 조각 수 | `FAKE_OUTPUT_TOKENS` (50) |
| `ttft` | 첫 프레임(일반 응답은 본문) 전 지연 ms | `FAKE_TTFT_MS` (0) |
| `gap` | 스트림 조각 사이 지연 ms | `FAKE_CHUNK_INTERVAL_MS` (0) |
| `err` | 즉시 이 상태 코드로 오류 응답(400~599) | 없음 |
| `abort` | N번째 조각 뒤 종료 프레임 없이 연결을 끊는다 | 없음 |
| `streamerr` | N번째 조각 뒤 오류 프레임을 보내고 끝낸다(값 없으면 1) | 없음 |

OpenAI 형식에서 `in`은 캐시를 포함한 `prompt_tokens`로, Anthropic 형식에서는 캐시를 뺀 `input_tokens`와 `cache_read_input_tokens`로 나간다. 요청의 `max_tokens`가 `out`보다 작으면 거기서 잘라 `length`/`max_tokens`로 끝낸다. `FAKE_ERROR_RATE`(0~1)를 주면 그 확률로 500을 돌려준다.

```bash
curl -s http://localhost:9090/v1/chat/completions -H 'Content-Type: application/json' -d '{"model":"fake-small:in=120,cached=20,out=8","messages":[{"role":"user","content":"hi"}]}'
```

```bash
curl -N http://localhost:9090/v1/messages -H 'Content-Type: application/json' -d '{"model":"fake-small:out=5,gap=200,abort=3","max_tokens":64,"stream":true,"messages":[{"role":"user","content":"hi"}]}'
```

## 라이선스

[MIT](LICENSE)
