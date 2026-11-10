# 014 엑사원(K-EXAONE 2.5) 공급처 추가

- 명세 작성·승인·구현·검증: 2026-11-10. 실제 엑사원 호출 확인(완료 기준 5)은 키가 없어 보류했다. 가입 화면이 회사 이메일을 요구해 가입하지 못했다("남은 문제")
- 선행: 006(OpenAI 호환 중계와 공급처 설정), 012(로컬 Ollama 단가 0 행과 예시 파일 테스트)
- 성격: 1단계 범위 안의 작은 작업이다. 브리프의 "상류 공급처: OpenAI 호환 엔드포인트"에 공급처 하나를 더한다. 2단계 계획과는 별개다

## 목표

LG AI연구원이 2026-11-09에 공개한 EXAONE API의 K-EXAONE 2.5를 게이트웨이의 공급처로 붙인다. 이 API는 OpenAI 호환 `POST /v1/chat/completions`라 중계 코드는 바꾸지 않고, 공급처 설정·환경 변수·요금표 행·테스트·문서만 더한다. 도구는 base URL과 가상 키만 바꾸고 `model`에 `k-exaone_v2.5`를 쓰면 되고, 요청마다 토큰과 비용(현재 0)이 기록된다.

## 완료 기준

1. `EXAONE_API_KEY`가 있으면 `k-exaone_v2.5` 요청이 `https://api.exaone.ai/v1/chat/completions`로 가고, 없으면 503 `provider_disabled`(메시지에 변수 이름)로 거절된다. 다른 모델의 라우팅은 그대로다(`gpt-*` → openai, 그 외 → ollama).
2. 문서의 예시 응답(일반·스트리밍·오류)으로 쓴 단위 테스트가 통과한다: 사용량 파싱(세부가 `null`, 최상위 `reasoning_tokens`), 스트림 관찰(`reasoning_content` 조각은 첫 내용으로 세지 않음, 사용량 조각 읽기와 걸러 내기), 오류 본문(`result_code` 형식).
3. 예시 요금표에 `exaone/k-exaone_v2.5` 단가 0 행이 있어 기록의 비용이 "단가 없음"이 아니라 0으로 보인다.
4. 기존 테스트가 그대로 통과하고(서버 128, 가짜 공급처 40, 대시보드 28에 이 작업의 새 테스트가 더해진다) 스모크가 통과한다. 테스트와 스모크는 엑사원을 부르지 않는다.
5. 키가 준비되어 있으면 실제 호출 2건(일반 1, 스트리밍 1)으로 기록을 확인하고 값을 적는다. 키가 없으면 이 항목만 보류로 적고 통과했다고 쓰지 않는다.
6. README에 사용법과 약관상 주의가 있고, 확인한 문서 내용이 이 기록에 있다.

## 명세

### 1. 파일

| 파일 | 변경 |
|---|---|
| `server/src/main/resources/application.yml` | `tollgate.providers`에 `exaone` 항목 추가(2절) |
| `docker-compose.yml` | `server` 서비스에 `EXAONE_API_KEY: ${EXAONE_API_KEY:-}` 전달 |
| `.env.example` | `EXAONE_API_KEY=`(빈 값)와 설명 주석 |
| `config/prices.example.yaml` | `exaone` / `k-exaone_v2.5` 단가 0 행과 머리 주석 한 줄(4절) |
| `server/src/test/.../pricing/PriceFileParserTest.kt` | 예시 파일의 공급처 집합 기대값에 `exaone` 추가(012에서 겪은 문제 4와 같은 자리) |
| `server/src/test/.../upstream/ProviderRegistryTest.kt` | `k-exaone_v2.5` 라우팅, 키 없을 때 비활성, 공급처별 응답 대기 시간 |
| `server/src/test/.../upstream/ShippedProvidersTest.kt` (새 파일) | 출고되는 `application.yml`을 실제로 바인딩해 엑사원 항목과 기존 라우팅을 확인(5절) |
| `server/src/test/.../relay/openai/OpenAiUsageParserTest.kt` (새 파일) | 엑사원 `usage` 예시 파싱 |
| `server/src/test/.../relay/openai/OpenAiStreamObserverTest.kt` | 엑사원 스트림 예시(추론 조각 포함) |
| `server/src/test/.../relay/openai/OpenAiAdapterTest.kt` (새 파일) | 일반 응답 예시와 `result_code` 오류 본문 |
| `examples/requests.http` | 엑사원 일반·스트리밍 요청 두 개 |
| `README.md` | "엑사원(K-EXAONE 2.5) 쓰기" 절, 공급처 기본 설정 문장에 엑사원 추가 |
| `docs/dev-log/014-exaone-provider.md` | 이 문서에 결정·겪은 문제·검증 결과·남은 문제 추가 |

운영 코드(`server/src/main/kotlin`)와 대시보드는 바꾸지 않는다. 새 의존성은 없다.

### 2. 공급처 설정

```yaml
    - name: exaone
      type: openai
      base-url: https://api.exaone.ai
      api-key-env: EXAONE_API_KEY
      models: ["k-exaone*"]
      response-timeout: 300s
```

- `name`은 기록과 요금표의 `provider`가 된다. `models` 패턴 `k-exaone*`는 지금의 모델 이름 `k-exaone_v2.5`와 이후 버전을 받는다.
- 키가 비어 있으면 이 공급처는 비활성이고, 패턴에 맞는 요청은 기본 공급처(ollama)로 흘러가지 않고 503 `provider_disabled`가 된다(006의 규칙 그대로).
- 게이트웨이는 `base-url`에 클라이언트가 부른 경로(`/v1/chat/completions`)를 붙이고 `Authorization: Bearer <키>`를 넣는다. 엑사원 문서의 주소·인증 방식과 같다.

### 3. 응답 대기 시간

K-EXAONE 2.5는 항상 추론을 거치고 `reasoning_effort`의 기본값이 `max`다. 일반(비스트리밍) 요청은 추론과 답이 모두 끝난 뒤에야 응답이 온다고 보는 것이 자연스럽고, 그러면 전역 `response-timeout` 60초에 걸려 504 `upstream_timeout`이 날 수 있다. 이 공급처만 300초로 둔다.

- 300초는 측정값이 아니라 출발값이다. nginx의 `/v1/` 읽기 제한(600초, 011)보다 짧게 잡았고, 실제 호출 뒤 걸린 시간을 보고 조정한다.
- 스트리밍은 응답 헤더가 바로 오고 추론 조각이 계속 흐르므로 `stream-idle-timeout`(60초)은 그대로 둔다.
- "일반 응답은 다 끝난 뒤에 온다"는 문서에 적힌 사실이 아니라 추정이다. 실제 호출로 확인해 적는다.

### 4. 요금표

- `provider: exaone`, `model: k-exaone_v2.5`, 입력·캐시 입력·출력 단가 모두 0, `effective_from: 2026-11-09`(API 공개일. UTC 자정이라 한국 시간 11-09 09시부터 적용).
- 머리 주석에 "엑사원 API는 2026-11-09 공개 시점에 무료(변경 이력을 2026-11-10에 확인)라 0으로 적었다. 유료로 바뀌면 `effective_from`이 더 늦은 행을 더한다"를 적는다. 약관에 유료 전환 가능성이 있다.
- 단가 조회는 응답의 `model`(`k-exaone_v2.5`)과 정확히 일치해야 한다(004).

### 5. 테스트 (네트워크 없음)

| 테스트 | 내용 |
|---|---|
| `ProviderRegistryTest` (+1) | 키가 있으면 `k-exaone_v2.5` → `exaone`, 키가 없으면 `Route.Disabled`(ollama로 가지 않음), 공급처 항목의 `responseTimeout`이 전역 값을 덮음 |
| `ShippedProvidersTest` (새 파일) | `application.yml`을 읽어 `ProviderProperties`로 바인딩한 뒤: `k-exaone_v2.5` → `exaone`(주소 `https://api.exaone.ai`, 응답 대기 300초), `gpt-6-astra` → `openai`, `qwen3:4b` → `ollama`, Anthropic 형식 `claude-haiku-5-5` → `anthropic`. 통합 테스트는 공급처 목록을 통째로 바꿔 쓰므로 출고 설정의 오타를 잡는 테스트가 지금은 없다 |
| `OpenAiUsageParserTest` (새 파일) | 문서 예시 `{"completion_tokens": 218, "prompt_tokens": 22, "total_tokens": 240, "completion_tokens_details": null, "prompt_tokens_details": null, "reasoning_tokens": 209}` → 입력 22, 출력 218, 캐시 0. 기존 형식(캐시 세부 있음)과 숫자가 없는 경우도 함께 |
| `OpenAiStreamObserverTest` (+1) | 문서의 스트림 예시: 빈 `content`의 역할 조각과 `reasoning_content` 조각은 첫 내용이 아님, 첫 `content` 조각에서 첫 내용 시각이 잡힘, `matched_stop`이 붙은 종료 조각 통과, `choices: []` 사용량 조각을 읽고(22/234) 클라이언트가 요청하지 않았으면 걸러 냄, 모델 `k-exaone_v2.5`, `[DONE]` |
| `OpenAiAdapterTest` (새 파일) | 문서의 일반 응답 예시 → 모델과 사용량. 오류 본문 `{"result_code": 429, "description": "..."}` → 오류 종류 없음(엔진이 `http_429`로 기록한다, `RelayEngine`의 기존 규칙) |
| `PriceFileParserTest` | 기대 공급처 집합에 `exaone` |

가짜 공급처에 추론 조각을 흉내 내는 지시는 더하지 않는다. 중계 경로 자체는 006의 통합 테스트가 이미 덮고 있고, 이번에 새로 확인할 것은 엑사원 응답 모양의 해석이라 단위 테스트로 충분하다.

### 6. 실제 호출 확인 (키가 있을 때, 실행 전 확인을 받는다)

키는 사용자가 발급해 `.env`의 `EXAONE_API_KEY`에만 넣는다. 대화, 로그, 문서, 커밋에는 넣지 않는다. 확인은 가상 키로 게이트웨이에만 보낸다.

1. 키를 넣기 전: `k-exaone_v2.5` 요청이 503 `provider_disabled`이고 메시지에 `EXAONE_API_KEY`가 나오는지 본다(외부 호출 없음).
2. 사용자가 키를 넣고 `docker compose up -d --build server`. 시작 로그에 "provider exaone enabled … key=set"만 남는지 본다.
3. 일반 1건과 스트리밍 1건을 보낸다. 짧은 프롬프트, `reasoning_effort: "low"`, `max_tokens: 512`, 태그 `exaone-check`.
4. 확인할 것: 상태 200, `x-tollgate-request-id`, 한도 헤더(`x-day-rate-limit-remaining` 등)가 클라이언트까지 오는지, 기록의 공급처 `exaone`·모델·입력/출력 토큰이 응답의 `usage`와 같은지, 비용 0, 스트리밍의 TTFT와 소요 시간, 일반 응답의 소요 시간(3절의 추정 확인).
5. 그 이상은 보내지 않는다. 약관이 승인 없는 부하 시험을 금지하고, 문서 예시의 한도가 초당 1회다. 부하·장애 시험은 지금처럼 가짜 공급처로만 한다.

### 7. README

- "로컬 Ollama 쓰기" 다음에 "엑사원(K-EXAONE 2.5) 쓰기" 절: 키 발급 위치(docs.exaone.ai의 "API Key 발급하기"), `.env`의 `EXAONE_API_KEY`, 모델 이름, 예시 스크립트 실행법(`python examples/openai_sdk_check.py http://localhost:8080/v1 k-exaone_v2.5`).
- 동작상 주의: 항상 추론하고 기본 강도가 `max`라 느리고 출력 토큰이 많다(추론 토큰이 출력 토큰에 포함된다). 빠른 확인에는 `reasoning_effort: "low"`. TTFT는 추론이 끝난 뒤 첫 답변 글자 기준으로 기록된다. 오류 본문은 `result_code`·`description` 형식 그대로 전달되고 기록에는 `http_<상태>`로 남는다. 한도 헤더는 그대로 통과한다.
- 약관 요지: 비상업적 이용만, 본인만 사용(키 공유와 제3자 제공 금지이므로 엑사원으로 가는 가상 키를 남에게 주지 않는다), 키는 30일마다 재발급, 입력과 결과가 기본적으로 모델 학습에 쓰이며 플랫폼에서 거부할 수 있음, 타인의 개인정보 입력 금지, 부하 시험 금지. 약관 원문 링크를 함께 둔다.
- "중계와 공급처 설정"의 기본 설정 문장에 `k-exaone*` → 엑사원(`EXAONE_API_KEY`)을 더한다.

### 8. 하지 않는 것

- 추론 토큰(`usage.reasoning_tokens`)을 따로 기록하는 것. 스키마와 대시보드가 바뀌므로 2단계에서 제안한다(012 남은 문제의 "reasoning 조각을 구분해 기록할지"와 같은 항목).
- TTFT 정의 변경(추론 조각을 첫 토큰으로 볼지). 지금 정의를 유지하고 README에 뜻을 적는다.
- EXAONE Tabular. 채팅 완성 API가 아니라 게이트웨이가 중계하는 경로 밖이다(그 문서는 읽지 않았다).
- 429 재시도·백오프, 레이트리밋, 키 만료 알림(3단계 범위).
- Anthropic 형식으로 엑사원을 부르는 변환(3단계의 형식 변환).
- OpenCode 같은 에이전트를 게이트웨이에 붙이는 시험. 이 작업이 끝난 뒤 별도 작업으로 제안한다.

### 9. 승인이 필요한 항목 (2026-11-10 모두 승인됨)

1. 공급처 이름 `exaone`, 패턴 `k-exaone*`, 키 변수 `EXAONE_API_KEY`(2절).
2. 이 공급처의 응답 대기 300초. 측정 전 출발값이며 실제 호출 뒤 조정한다(3절).
3. 예시 요금표에 단가 0 행, `effective_from: 2026-11-09`(4절).
4. 새 의존성 없음. 테스트는 문서의 예시 응답만 쓰고 네트워크를 타지 않는다. 새 테스트 파일 셋(5절).
5. 실제 호출은 사용자가 키를 `.env`에 넣은 뒤, 보내기 직전에 확인을 받고 2건만 한다. 키가 아직 없으면 그 검증만 보류로 적는다(6절).
6. README에 약관 요지(비상업적 이용, 본인만 사용 등)를 적는다(7절).
7. `feature/014-exaone-provider` 브랜치에서 작업하고 커밋·PR은 요청 시.

## 확인한 공식 문서 (2026-11-10, docs.exaone.ai)

- [Changelog](https://docs.exaone.ai/ko/docs/changelog): 2026-11-09 공개, "본 서비스는 현재 무료로 제공됩니다", 제공 모델 K-EXAONE 2.5(750B)와 EXAONE Tabular.
- [Quick Start](https://docs.exaone.ai/ko/docs/quick-start)·[Authentication](https://docs.exaone.ai/ko/docs/authentication): 기본 URL `https://api.exaone.ai`, `Authorization: Bearer <API key>`, 요청 `application/json`, 응답 `application/json` 또는 `text/event-stream`. 예시의 환경 변수 이름이 `EXAONE_API_KEY`다.
- [K-EXAONE 2.5](https://docs.exaone.ai/ko/docs/k-exaone-2.5): `POST /v1/chat/completions`, "OpenAI Compatible API를 지원", 모델 `k-exaone_v2.5`, 텍스트 전용, 컨텍스트 524,288 토큰(입력 + `max_tokens`가 넘으면 400). 요청 필드: `model`, `messages`, `max_tokens`(추론 포함), `stream`, `stream_options.include_usage`(스트리밍에서만), `temperature`(기본 1.0), `top_p`(0.95), `top_k`, `presence_penalty`, `reasoning_effort`(`low`/`high`/`max`, 기본 `max`), `chat_template_kwargs.preserve_thinking`.
- 같은 문서의 응답: 추론은 `message.reasoning_content`와 스트림의 `delta.reasoning_content`로, 답은 `content`로 온다. `content` 앞에 `"\n\n"`이 붙을 수 있다. `usage`는 `prompt_tokens`, `completion_tokens`, `total_tokens`, `reasoning_tokens`(최상위), `completion_tokens_details`·`prompt_tokens_details`는 예시에서 `null`. 스트림은 역할 조각(`content: ""`) → 추론 조각들 → 내용 조각들 → 종료 조각(`finish_reason`, `matched_stop`) → 사용량 조각(`choices: []`) → `data: [DONE]`. 예시에서 `completion_tokens` 218 중 `reasoning_tokens`가 209라 추론 토큰이 출력 토큰에 포함된다.
- [Error Codes](https://docs.exaone.ai/ko/docs/error-codes): 400, 401, 404, 422, 429, 500. 본문은 `{"result_code": 500, "description": "..."}` 형식으로 OpenAI의 `error` 객체가 아니다.
- [Rate Limits](https://docs.exaone.ai/ko/docs/rate-limits): 초과 시 429. 헤더 `x-{second|minute|day}-rate-limit`, `-remaining`, `retry-after`, `retry-after-ms`, `x-request-id`. 예시 값은 초 1·분 60·일 86,400이지만 실제 한도는 문서에 적혀 있지 않다. 이용 기록 분석에 선택 동의하면 일일 한도가 늘어난다.
- [Models](https://docs.exaone.ai/ko/docs/models): 컨텍스트 512K, 최대 출력 512K, 지식 기준 2026-01, 기능 Chat·Tool calling·Structured outputs·Code generation·Streaming.
- [Agent로 시작하기](https://docs.exaone.ai/ko/docs/agent-start): Hermes와 OpenCode를 `https://api.exaone.ai/v1`에 OpenAI 호환 공급처로 붙이는 방법.
- [이용약관](https://docs.exaone.ai/ko/terms/terms-of-service)(2026-11-09 시행): 비상업적 이용 권한이며 제3자가 모델을 호출하게 하는 연동·재제공 금지(2조 1·5항), 입력과 결과를 회사가 학습에 이용할 수 있고 플랫폼에서 거부 가능(2조 3항), 키 공유 금지와 유효기간 30일(3조 3·4항), 이용 한도 회피와 승인 없는 부하·보안 시험 금지(4조 2항 i·j), 타인의 개인정보 입력 금지(7조 8항), 만 19세 이상(8조), 유료 전환 가능(9조 2항). 약관 해석은 법률 판단이 아니라 읽은 내용의 요약이다.
- 확인하지 못한 것: 로그인 뒤의 키 발급 화면(발급 버튼은 `id.exaone.ai` 로그인으로 넘어가며 계정 생성과 로그인은 사용자가 한다), 실제 한도 숫자, EXAONE Tabular 문서, 일반 응답이 언제 오는지(3절의 추정).

## 코드와 대조한 사실 (2026-11-10)

- `OpenAiUsageParser`는 `prompt_tokens`·`completion_tokens`가 숫자면 사용량을 만들고, `prompt_tokens_details`가 `null`이면 캐시 0으로 둔다. 엑사원의 `usage`가 그대로 읽힌다.
- `OpenAiStreamObserver`는 `choices`가 비고 `usage`가 있는 조각을 사용량으로 읽고, 첫 내용은 비어 있지 않은 `delta.content`나 `tool_calls`로 판단한다. `reasoning_content` 조각은 첫 내용이 아니므로 TTFT는 추론이 끝난 뒤 첫 답변 조각 시각이 된다(012의 Qwen3와 같은 동작).
- `OpenAiRequestInspector`는 스트리밍 요청에 `stream_options.include_usage`를 켜고 다른 필드를 보존한다. 엑사원이 이 옵션을 지원하므로 주입 방식이 그대로 통한다. `reasoning_effort`·`chat_template_kwargs` 같은 추가 필드도 그대로 간다.
- `OpenAiAdapter.parsePlain`은 오류 본문에서 `error.type`만 읽는다. 엑사원 오류에는 없으므로 `RelayEngine`이 `http_<상태>`로 기록한다.
- `HeaderRules`는 응답 헤더 중 `content-length` 등 네 개만 빼고 전달한다. 한도 헤더와 `retry-after`가 클라이언트까지 간다.
- `ProviderConfig`에 공급처별 `responseTimeout`이 이미 있다(006). 설정만으로 덮을 수 있다.

## 결정과 이유

- 엑사원 항목은 설정에서 `openai` 다음에 두었다. 패턴 일치를 모든 항목에서 먼저 찾고 그다음에 기본 공급처를 보므로 순서는 결과에 영향을 주지 않고, 키가 필요한 OpenAI 호환 공급처끼리 모아 읽기 쉽게 했다.
- 응답 대기 300초는 측정 없이 정한 출발값이다. 실제 호출을 한 뒤 걸린 시간을 보고 고친다. 전역 값(60초)을 올리지 않은 이유는 다른 공급처의 느린 실패까지 늦게 알게 되기 때문이다.
- 요금표의 `effective_from`은 API 공개일(2026-11-09)로 했다. Ollama 행처럼 넉넉히 앞 날짜를 둘 수도 있었지만, 공개 전 날짜는 사실이 아니고 UTC 자정 함정(012 겪은 문제 1)은 이미 지난 날짜라 해당하지 않는다.
- `ShippedProvidersTest`는 `MockEnvironment`에 `application.yml`을 얹어 `Binder`로 묶는다. 시스템 환경 변수를 읽지 않으므로 개발 PC에 실제 키가 있어도 CI와 같은 결과가 나오고, 키가 필요한 경우에는 가짜 값만 넣는다. 통합 테스트(`FakeUpstreamInitializer`)가 공급처 목록을 통째로 바꿔 쓰기 때문에 출고 설정의 패턴 오타나 가려진 패턴을 잡는 테스트가 그동안 없었다.
- 테스트의 응답 본문은 문서 예시를 줄여 썼다(추론 본문은 한 문장으로). 일반 응답은 Quick Start의 예시를 기준으로 했다. 엔드포인트 문서의 같은 예시는 `"tool_calls": null,` 뒤에 쉼표가 남아 있어 그대로는 JSON이 아니다.
- 계획서 000의 변경 이력에는 항목을 더하지 않았다. 012에서 1단계를 닫았고 이 작업은 그 뒤의 추가라 이 기록과 README가 근거다.
- 키 없는 상태 확인에 쓴 프로젝트 `exaone-check`와 가상 키는 로컬 DB에만 있다. 실제 호출 확인이 뒤로 밀렸으므로 그때는 가상 키를 새로 발급한다.
- 실제 호출을 못 했다는 사실을 코드 가까이에도 남겼다. `application.yml`의 엑사원 항목과 `examples/requests.http`의 엑사원 요청 위에 TODO 주석을 두었고, README의 엑사원 절에도 "아직 실제 키로 불러 보지 못했다"고 적었다. 설정만 보고 검증된 연동으로 읽히지 않게 하기 위해서다.

## 겪은 문제

1. Docker Desktop이 내려가 있었다(날이 바뀌면 내려가는 패턴). 샌드박스 밖에서 띄운 뒤 전체 테스트와 스모크를 돌렸다.
2. 키를 받지 못했다. EXAONE ID 가입 화면의 이메일 칸이 "회사 이메일"이고 회사 계정으로 가입하라고 안내해, 사용자가 가입을 진행하지 않았다. 그래서 실제 호출 확인(명세 6절의 2~4)을 하지 못했고 TODO로 남겼다.

구현 자체에서 막힌 곳은 없었다. 새 테스트는 첫 실행에서 통과했다.

## 실행한 검증과 결과 (2026-11-10)

| 검증 | 명령 | 결과 |
|---|---|---|
| 새·변경 단위 테스트 | `./gradlew :server:test --tests …` | `ProviderRegistryTest` 7(+1), `ShippedProvidersTest` 3, `OpenAiUsageParserTest` 4, `OpenAiAdapterTest` 4, `OpenAiStreamObserverTest` 5(+1), `PriceFileParserTest` 5. 실패 0 |
| 전체 테스트 | `./gradlew test --rerun-tasks` | BUILD SUCCESSFUL 39초. server 141개(128에서 +13), fake-provider 40개, 실패 0 |
| 스모크 | `bash scripts/smoke.sh`(서버 이미지 재빌드 포함) | 13단계 PASS. 엑사원은 부르지 않는다 |
| 시작 로그 | `docker compose logs server` | "provider exaone (OPENAI) disabled: environment variable EXAONE_API_KEY is empty", 요금표 "1 rows inserted, 13 already present" |
| 키 없는 요청 | 가상 키로 `k-exaone_v2.5` 요청(외부 호출 없음) | 503, `{"error":{"message":"Provider 'exaone' is disabled: environment variable EXAONE_API_KEY is empty","type":"server_error","param":null,"code":"provider_disabled"}}`, `x-tollgate-request-id` 헤더 |
| 그 요청의 기록 | `GET /admin/v1/usage/records?tag=exaone-check` | provider `exaone`, 모델 `k-exaone_v2.5`, 상태 503, `gateway_error`/`provider_disabled`, 비용 null, 사용량 출처 `none` |
| 공급처 목록 | `GET /admin/v1/providers` | `exaone` / `openai` / 비활성 / `["k-exaone*"]`. 키 관련 필드 없음 |
| 요금표 행 | `GET /admin/v1/prices?provider=exaone` | `k-exaone_v2.5`, 입력·출력 0, 적용 시작 2026-11-09T00:00:00Z |

- TODO 주석과 문서를 보완한 뒤 `./gradlew test --rerun-tasks`(BUILD SUCCESSFUL 44초, server 141개·fake-provider 40개, 실패 0)와 `bash scripts/smoke.sh`(13단계 PASS)를 다시 돌렸다.
- 대시보드는 바꾸지 않아 다시 돌리지 않았다(28개는 013 시점 그대로이고 CI에서 확인한다).
- 실제 엑사원 호출은 하지 않았다. `.env`에 `EXAONE_API_KEY`가 없다.
- 측정 조건: Windows 11 Home, JDK Temurin 25, Gradle 9.8.2, Docker Desktop 엔진 29.9.2, PostgreSQL 18(compose와 Testcontainers).

## 남은 문제

- 실제 호출 확인(명세 6절의 2~4)이 남아 있다. 키를 발급해 `.env`에 넣은 뒤 일반 1건과 스트리밍 1건으로 다음을 확인하고 이 기록에 값을 적는다: 상태와 요청 ID, 한도 헤더의 통과, 기록의 토큰 수가 응답의 `usage`와 같은지, 비용 0, TTFT와 소요 시간, 그리고 응답 대기 300초가 맞는 값인지. 그 전까지 완료 기준 5는 채워지지 않은 상태다.
- 키를 받을 방법이 정해지지 않았다. 가입 화면이 회사 이메일을 요구해 2026-11-10에는 가입하지 못했다(겪은 문제 2). 개인 메일로 가입되는지, 가입 조건이 바뀌는지는 확인하지 못했다. 키가 생기기 전까지 이 공급처는 비활성으로 뜨고 `k-exaone*` 요청은 503으로 거절된다.
- 키가 30일 뒤 만료되면 엑사원이 401을 돌려주고 기록에는 `upstream_error`/`http_401`로 남는다. 게이트웨이가 만료를 미리 알려 주지는 않는다.
- 실제 한도 숫자를 모른다. 첫 호출의 `x-*-rate-limit` 헤더로 확인해 적는다. 429를 받았을 때의 재시도나 대기는 없다(3단계 범위).
- 추론 토큰을 따로 기록하지 않아 대시보드의 출력 토큰에 추론분이 섞여 보인다. TTFT도 추론이 끝난 시점이다. 2단계 계획에서 `reasoning_tokens` 열과 TTFT 정의를 함께 제안한다.
- OpenCode처럼 OpenAI 호환 에이전트를 게이트웨이에 붙이면 브리프 §8의 "개발에 쓰는 에이전트 호출을 게이트웨이로"를 비용 없이 시험할 수 있다. 별도 작업으로 제안한다.
- 문서는 공개 다음 날(2026-11-10)에 읽은 내용이다. 필드나 한도, 약관이 바뀔 수 있으니 쓰기 전에 다시 확인한다.
