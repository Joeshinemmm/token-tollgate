# 012 실제 도구 연결과 1단계 마무리

- 명세 작성·승인·구현·검증: 2026-11-08
- 선행: 001~011 전부. compose 스택이 돌고 CI가 녹색이며, 이 PC에 Ollama 0.40.15와 모델 `qwen3:4b`(2.5GB)·`qwen3:0.6b`(0.52GB)가 설치되어 있다
- 후속: 2단계 계획(예산 한도, 공급처 폴백 등)은 별도 문서로 시작한다. 이 작업이 1단계의 마지막이다

## 목표

브리프 완료 기준 3("실제 도구 하나를 base URL만 바꿔 연결해 기록이 쌓이는 것을 확인했다")을 채우고, 완료 기준 1~5를 증거와 함께 대조해 1단계를 닫는다. 실제 도구는 두 겹이다. 공급처 쪽은 로컬 Ollama(비용 0), 클라이언트 쪽은 공식 OpenAI SDK다. 둘 다 코드 변경 없이 주소와 키만 바꿔 게이트웨이를 지나간다. 마지막으로 열한 개 dev-log에 흩어진 "남은 문제"를 한곳에 모아 2단계로 넘길 것과 닫을 것을 가른다.

## 완료 기준

1. OpenAI SDK(Python)로 base URL을 `http://localhost:8080/v1`, API 키를 가상 키로 바꿔 `qwen3:4b`에 일반·스트리밍 요청을 보내면 응답이 오고, 대시보드 요청 목록에 공급처 `ollama`, 토큰 수(캐시 포함), 비용 0으로 기록된다.
2. compose 스택(컨테이너 안 서버 → 호스트 Ollama)과 개발 모드(`bootRun` → `localhost:11434`) 두 경로 모두 위 요청이 통과한다.
3. `config/prices.example.yaml`에 로컬 모델 단가 0 행이 있어, 새로 받은 사람도 Ollama 기록이 "단가 없음"이 아니라 비용 0으로 보인다.
4. README 상태 절이 "1단계 완료"가 되고, 완료 기준 1~5의 대조표가 dev-log 012에 있다.
5. 000 계획서에 작업 012 변경 이력이 있고, dev-log 012의 "1단계를 닫으며" 절이 각 dev-log의 남은 문제를 닫힘/2단계 이관/보류로 분류한다.
6. 서버·대시보드 코드는 바꾸지 않는다. 기존 테스트(서버 128, 가짜 공급처 40, 대시보드 28)와 CI가 그대로 녹색이다.

## 명세

### 1. 파일

| 파일 | 변경 |
|---|---|
| `config/prices.example.yaml` | `ollama` 공급처의 `qwen3:4b`, `qwen3:0.6b` 행을 단가 0으로 추가(2절) |
| `examples/requests.http` | Ollama 모델로 보내는 일반·스트리밍 요청 두 개 추가 |
| `examples/openai_sdk_check.py` | OpenAI SDK로 게이트웨이를 거쳐 일반·스트리밍 한 번씩 보내고 `x-tollgate-request-id`를 찍는 20줄 스크립트(3절) |
| `README.md` | 상태 절을 1단계 완료로, "로컬 Ollama 쓰기" 짧은 절, SDK 연결 예시 링크 |
| `docs/dev-log/000-phase1-plan.md` | §8 변경 이력에 012 항목 |
| `docs/dev-log/012-ollama-and-phase1-wrapup.md` | 이 문서에 결과·대조표·마무리 절 추가 |

### 2. 로컬 모델 단가

- Ollama 모델은 API 과금이 없으므로 `input_per_mtok: 0`, `output_per_mtok: 0`, `cached_input_per_mtok: 0`을 둔다. 서버의 단가 규칙은 "음수 불가, 소수 8자리"라 0이 허용된다(004). 비용이 0으로 계산되어 개요 카드의 "단가 없음" 경고에서 빠지고 총 비용에는 0이 더해진다.
- 행은 `qwen3:4b`와 `qwen3:0.6b` 둘만 넣고, 다른 모델을 쓰는 사람은 요금표 화면이나 `PUT /admin/v1/prices`로 더하라고 주석에 적는다. 단가 조회가 정확 일치라 와일드카드는 없다(004 남은 문제, 그대로 둔다).
- `effective_from`은 2026-11-08. 파일 머리의 "공식 가격 페이지에서 옮긴 값" 설명에 "로컬 모델은 0" 한 줄을 보탠다.

### 3. 실제 도구 두 겹

**공급처: 로컬 Ollama.** 서버 설정은 이미 "어느 패턴에도 안 맞는 OpenAI 형식 모델명 → `ollama`(기본 공급처, `OLLAMA_BASE_URL`)"이라 바꿀 것이 없다. 오늘 확인한 사실: Ollama 0.40.15의 `/v1/chat/completions`는 일반 응답에 `usage`(`prompt_tokens`, `prompt_tokens_details.cached_tokens`, `completion_tokens`)를 주고, `stream_options.include_usage`를 켜면 스트림 끝에 `choices: []`인 사용량 조각을 준다. 게이트웨이가 006에서 구현한 방식 그대로라 사용량이 기록된다. compose의 서버 컨테이너에서 `host.docker.internal:11434`가 닿는 것도 확인했다(Ollama 기본 설정 그대로, "Expose to the network" 불필요).

**클라이언트: OpenAI 공식 SDK.** `examples/openai_sdk_check.py`는 `OpenAI(base_url="http://localhost:8080/v1", api_key=<가상 키>)`로 일반 한 번, 스트리밍 한 번(`stream_options={"include_usage": True}`)을 보내고 응답 헤더의 `x-tollgate-request-id`와 `usage`를 출력한다. 가상 키는 환경 변수 `TOLLGATE_VIRTUAL_KEY`로 받고 출력하지 않는다. 실행 전에 `pip install openai`가 필요하다(승인 항목). 이 PC에는 아직 없다.

**선택: Claude Code를 게이트웨이로.** 브리프 §8의 "개발에 쓰는 에이전트 호출을 게이트웨이로"의 첫 시도로, Claude Code의 `ANTHROPIC_BASE_URL`을 `http://localhost:8080`으로, 키를 가상 키로 바꿔 짧은 질문 한 번을 보내 보는 것이다. 실제 Anthropic 과금이 있고 사용자 터미널에서 직접 해야 하므로 사용자가 원할 때만 한다. 되면 대조표에 적고, 안 되면(헤더·베타 기능 차이) 2단계 과제로 적는다.

### 4. 확인 절차

1. `pip install openai` 후 `python examples/openai_sdk_check.py`를 compose 스택(8080)에 대고 실행. 요청 ID 두 개를 얻는다.
2. 대시보드(3000) 요청 목록에서 두 요청을 찾아 공급처 `ollama`, 모델 `qwen3:4b`, 입력·캐시·출력 토큰, 비용 `0.00`, TTFT·소요 시간을 읽는다. 개요에서 모델별 비용 내역에 `ollama/qwen3:4b` 행이 생기는 것을 본다.
3. compose 서버를 내리고 `bootRun`(호스트)으로 띄워 같은 스크립트를 한 번 더 돌린다(`localhost:11434` 경로).
4. 응답 시간을 적는다(qwen3:4b, RTX 5060 Ti, 짧은 프롬프트). Qwen3는 생각(reasoning)을 먼저 하는 모델이라 짧은 질문에도 출력 토큰이 수백~천 개가 되는데, 그것도 그대로 기록되는지 본다.
5. 스모크(`scripts/smoke.sh`)와 대시보드 테스트를 다시 돌려 기존 것이 그대로인지 본다. 서버 코드는 바뀌지 않으므로 서버 테스트는 CI에 맡긴다.

### 5. 완료 기준 대조표 (2026-11-08 채움)

| 기준 | 증거 | 상태 |
|---|---|---|
| 1. `docker compose up` 한 번으로 네 서비스 기동 | 011: `up -d --build --wait` 17초(캐시), 스모크 1단계 "all services healthy", CI 스모크 잡 녹색 | 충족 |
| 2. 두 형식 × 일반·스트리밍 중계와 요청별 기록 | 006·007 통합 테스트(서버 128개), 011 스모크의 중계 5건 모두 `outcome=ok`·비용 기록, 007과 2026-11-08의 실제 Anthropic 호출 4건 | 충족 |
| 3. 실제 도구를 base URL만 바꿔 연결 | 012: OpenAI SDK 3.46.0(`base_url`·`api_key`만 변경) → 게이트웨이 → Ollama 0.40.15, compose와 bootRun 두 경로에서 6건 기록, 비용 0 | 충족 |
| 4. 대시보드에서 키 발급·폐기, 기간·프로젝트·모델·태그별 비용 | 009·010 수동 확인과 테스트 28개, 사용자가 3000 포트에서 직접 확인, 012에서 `ollama/qwen3:4b` 행과 태그 `sdk-check` 목록 확인 | 충족 |
| 5. 테스트 통과와 dev-log | CI 세 잡 녹색(PR #10·#11, main), 서버 128·가짜 공급처 40·대시보드 28개, dev-log 000~012 | 충족 |

### 6. 1단계를 닫으며 (계획; 결과는 아래 같은 이름의 절)

열한 개 dev-log의 "남은 문제"를 세 묶음으로 나눈다.

- 닫힘: 1단계 안에서 해소된 것(예: 002의 compose 노출 여부 → 011에서 결정, 006의 가짜 공급처 두 종류 → 007에서 해결, 008의 빈 날짜 → 010에서 채움).
- 2단계 이관: 기능이 필요한 것(캐시 쓰기 단가와 구간별 단가, 끊긴 스트림의 부분 사용량 확정, 모델 별칭, `groupBy=hour`, 집계 테이블, 레이트리밋 저장소, 관리자 토큰 회전, 요청 목록 부분 일치 검색).
- 보류: 1단계 범위 밖이거나 사소한 것(JVM 이미지 크기, `RelayEngine` 분리, 가짜 공급처의 abort 로그, 복사 버튼의 일반 브라우저 확인은 사용자 확인으로 닫음 여부 결정, 요청 목록 열 폭, 세션 끊김 알림 문구).

각 항목에 출처 dev-log 번호를 달고, 2단계 계획을 쓸 때 이 절을 입력으로 쓴다. 측정값도 한곳에 모은다: CI 소요(PR #10 서버 138초·스모크 128초·대시보드 19초, PR #11 서버 65초·스모크 129초·대시보드 22초), 이미지 크기, 로컬 빌드 시간, 실제 Anthropic 호출 결과(007과 011 뒤 확인분), Ollama 응답 시간.

### 7. 승인이 필요한 항목

1. `config/prices.example.yaml`에 `ollama` 모델 두 개를 단가 0으로 추가(2절). 예시 파일이 "공식 가격" 외에 "로컬 모델은 0"이라는 규칙을 하나 더 담게 된다.
2. 클라이언트 도구로 OpenAI 공식 Python SDK를 쓰고, 이 PC에 `pip install openai`를 한다(프로젝트 의존성이 아니라 확인용 설치). 스크립트는 `examples/`에 둔다.
3. Claude Code 연결은 선택 사항으로 두고 사용자가 직접, 원할 때만 한다(실제 과금).
4. 서버·대시보드 코드는 바꾸지 않는다. 002의 "actuator show-components를 공개 환경에서 끄기"처럼 코드가 필요한 것은 2단계로 넘긴다.
5. README 상태를 "1단계 완료"로 바꾸고, 이 dev-log에 완료 기준 대조표와 남은 문제 분류를 둔다.

## 확인한 사실 (2026-11-08)

- Ollama 0.40.15(`/api/version`), 설치 모델 `qwen3:4b` 2.5GB·`qwen3:0.6b` 0.52GB(`/api/tags`). `/v1/chat/completions` 일반 응답: `usage.prompt_tokens` 15, `prompt_tokens_details.cached_tokens` 14, `completion_tokens` 1072("Say hello in one word"에 생각 토큰 포함), 메시지에 `reasoning` 필드. 스트리밍 + `stream_options.include_usage`: 마지막에 `choices: []`와 `usage`, `timings` 조각 뒤 `[DONE]`. `include_usage` 없이는 사용량 조각이 없다.
- compose의 `server` 컨테이너에서 `wget http://host.docker.internal:11434/api/version` → `{"version":"0.40.15"}`. Ollama 기본 바인딩 그대로 닿는다.
- 서버 설정: `ollama`는 `type: openai`, `anonymous: true`, `default: true`, `base-url: ${OLLAMA_BASE_URL:http://localhost:11434}`. 단가 조회는 `findEffective(provider, model, at)` 정확 일치.
- 이 PC: NVIDIA GeForce RTX 5060 Ti, RAM 63GB, Windows 11 Home 26200, C 여유 724GB. OpenAI SDK는 Python·npm 어느 쪽에도 설치되어 있지 않다.
- 공식 문서: docs.ollama.com/windows(설치 위치·요구 사항·로그 경로), docs.ollama.com/api/openai-compatibility(`base_url='http://localhost:11434/v1/'`, API 키는 필수지만 무시), docs.ollama.com/quickstart(로컬 모델은 계정 불필요).

## 결정과 이유

- 로컬 모델 단가 행의 `effective_from`은 설치일(11-08)이 아니라 `2026-11-01`로 뒀다. `effective_from`은 UTC 자정이라 한국 시간으로 그날 오전 9시 전에는 "아직 적용 전"이 되어 비용이 null로 남는다(겪은 문제 1). 예시 파일 주석에 이 함정을 적었다.
- 확인 스크립트는 `examples/openai_sdk_check.py`로 두고 프로젝트 의존성에는 넣지 않았다. `with_raw_response`로 응답 헤더의 `x-tollgate-request-id`를 읽어 대시보드 기록과 바로 맞춰 볼 수 있게 했고, 태그는 SDK의 `default_headers`로 `X-Tollgate-Tag: sdk-check`를 붙였다. 도구 쪽에서 바꾼 것은 `base_url`과 `api_key` 둘뿐이라는 것이 이 작업의 요점이다.
- Claude Code를 게이트웨이에 붙이는 시험은 하지 않았다(선택 사항, 실제 과금). 2단계 입력으로 남긴다.
- Ollama 모델 두 개만 요금표에 넣었다. 단가 조회가 정확 일치라 와일드카드가 없고, 사용자마다 받는 모델이 다르므로 "0 단가 행을 더하라"는 안내로 갈음했다.

## 겪은 문제

1. 첫 SDK 호출 두 건이 공급처 `ollama`로 기록됐지만 비용이 null이었다. 단가 행이 `effective_from: 2026-11-08`(UTC 자정)인데 호출 시각이 UTC로는 11-07 17시라 아직 적용 전이었다. 날짜를 11-01로 당기고, 이미 들어간 11-08 행은 관리 API로 지운 뒤 서버를 재시작해 다시 심었다. 대시보드의 "단가 없음 2건"은 이 두 건이다.
2. Git Bash 콘솔에서 스크립트의 한글 출력이 깨졌다. `PYTHONUTF8=1`로 돌리면 정상이고, PowerShell·IntelliJ 터미널에서는 문제없다. 스크립트는 그대로 두었다.
3. bootRun 경로 확인 때 compose의 `server`·`dashboard`를 멈춰야 했다(8080 충돌). 확인 뒤 `docker compose up -d server dashboard`로 되돌렸고 데이터는 같은 PostgreSQL이라 그대로다.
4. PR #12의 첫 CI 실행에서 서버 잡이 실패했다(`PriceFileParserTest > the shipped example file parses`, 128개 중 1개). 이 테스트는 예시 요금표를 실제로 읽어 공급처 집합이 `{openai, anthropic, fake}`와 같은지 단언하는데, `ollama` 행이 들어가자 집합이 달라졌다. 기대 집합에 `ollama`를 더했다(테스트 한 줄). 완료 기준 6의 "서버 코드는 바꾸지 않는다"는 운영 코드 기준으로는 지켰고 이 테스트 기대값이 유일한 예외다. 예시 파일에 공급처를 더하면 이 테스트도 같이 고쳐야 한다.

## 실행한 검증과 결과 (2026-11-08)

- compose 경로(서버 컨테이너 → `host.docker.internal:11434`): `qwen3:4b` 일반 4.06초(첫 호출)·2.66초, 스트리밍 TTFT 10.8초(첫 호출, 생각 토큰 1,368개)·2.45초(317개). 모두 `outcome=ok`, 입력 16·캐시 15(두 번째부터 프롬프트 캐시 적중)·출력 300~1,368, 단가 수정 뒤 비용 `0.0`. 요청 ID가 SDK 출력과 기록에서 일치.
- bootRun 경로(호스트 → `localhost:11434`): `qwen3:0.6b` 일반 18.5초(모델 적재 포함), 스트리밍 TTFT 0.41초·총 0.43초, 비용 `0.0`. 서버 로그 "provider ollama enabled: baseUrl=http://localhost:11434".
- 대시보드(3000, nginx 경유): 요청 목록 태그 `sdk-check` 4행(공급처 `ollama`, 모델 `qwen3:4b`, 토큰, 비용 0.00 또는 "—", TTFT/소요, "성공 200 · 스트림"), 개요에서 프로젝트 `ollama-check`의 비용 내역 모델 탭에 `ollama/qwen3:4b` 요청 4·비용 0.00.
- `PriceSeeder` 로그: 수정한 예시 파일로 재시작 시 "2 rows inserted, 11 already present"(compose), bootRun에서는 "0 rows inserted, 13 already present".
- 되돌린 compose 스택에서 `bash scripts/smoke.sh` 13단계 PASS(8초), 대시보드 테스트 28개 통과. 서버 운영 코드는 바꾸지 않았다. 첫 CI에서 실패한 `PriceFileParserTest`(겪은 문제 4)는 기대값을 고친 뒤 로컬에서 `./gradlew :server:test --tests tokentollgate.pricing.PriceFileParserTest`로 5개 통과를 확인했고, 서버 전체 128개와 CI 세 잡의 재실행은 PR #12 체크에서 확인한다.
- 설치: `pip install openai` → 3.46.0.

## 측정값 모음 (1단계 전체)

| 항목 | 값 | 조건 |
|---|---|---|
| 테스트 수 | 서버 128, 가짜 공급처 40, 대시보드 28 | `./gradlew test --rerun-tasks`, `vitest run` |
| CI 소요 | PR #10 서버 138초·스모크 128초·대시보드 19초; PR #11 서버 65초·스모크 129초·대시보드 22초 | ubuntu-latest, Gradle 캐시는 두 번째 실행부터 |
| 이미지 크기 | 서버 418MB, 가짜 공급처 394MB, 대시보드 93.6MB, postgres:18 650MB | `docker images`, Temurin 25 alpine / nginx 1.30.6 alpine |
| 로컬 이미지 빌드 | Gradle 스테이지 병렬 72초+56초, 직렬(`sharing=locked`) 30초→14초 | Docker Desktop 29.9.2, 캐시 마운트 |
| compose 기동 | 캐시된 이미지로 healthy까지 17초, 스모크 7~8초 | 이 PC |
| 대시보드 번들 | 메인 JS 268.6 kB(gzip 82.7) + 차트 청크 367.9 kB(gzip 106.1), CSS 11.4 kB | `vite build` |
| 실제 Anthropic | 007: 2건(입력 20·출력 4, 845 ms TTFT); 11-08: 18/6 토큰 1,261 ms, 20/16 토큰 859 ms, 예시 단가로 0.0000048·0.00001달러 | `claude-haiku-5-5`, 비스트림 |
| 로컬 Ollama | 위 "실행한 검증과 결과" | RTX 5060 Ti, Ollama 0.40.15 |

## 1단계를 닫으며

열한 개 dev-log와 계획서 §7의 남은 문제를 분류했다. 괄호는 출처 dev-log.

**닫힘 (1단계 안에서 해소)**

- 첫 커밋·GitHub 저장소·라이선스·Ollama 설치(001, 계획 §7): 모두 됨.
- 대시보드 템플릿(001) → 009·010. R2DBC·Flyway 확인(002) → 002 실행으로 해소.
- compose의 PostgreSQL 포트 노출 여부(002) → 011에서 노출로 결정(개발 모드용).
- 가짜 공급처를 두 종류로 등록할 때의 이름 문제(006) → 007에서 (이름, 종류) 식별로 해결.
- server 테스트에 가짜 공급처를 올리는 방식(005) → 006에서 `/fake` 접두사로 구현.
- `GET /v1/models`를 가짜 공급처가 주지 않는 것(005) → 게이트웨이가 요금표로 답하므로 불필요.
- 실패 요청의 모델 묶음 키(008) → 010에서 서버가 묶은 그대로 보여 주기로 결정.
- 기록 없는 날의 `day` 묶음(008) → 010 차트가 0으로 채움.
- `Route` 타입 확장(009) → 010. `.claude/launch.json`(009) → 저장소에 두지 않기로 함.
- CI 소요 시간 기록(011) → 위 측정값. `compose --wait`가 v2 필요(011) → README에 적음.
- 계획 3.1·3.2의 "확인 필요"(계획 §7) → 006·007에서 실제 응답으로 재확인.

**2단계로 이관 (기능이나 코드가 필요)**

- 요금: 프롬프트 길이 구간별 단가, Anthropic 캐시 쓰기 단가, 토큰 외 요금(서버 측 도구 호출), 공급처가 돌려주는 날짜 붙은 모델 id의 별칭(004·007).
- 끊긴 스트림의 누적 출력 토큰을 "받은 만큼" 확정(007).
- Anthropic 형식의 `GET /v1/models`(007). 모르는 `/v1` 경로의 404를 프로토콜 형식으로(003·006).
- 사용량 조회: `groupBy=hour`(010), `tag`·`model` 부분 일치(008).
- 운영: 공개 환경에서 actuator `show-components` 끄기(002), 관리자 토큰 회전(003), `last_used_at` 메모리 맵을 외부 저장소로(003, 레이트리밋과 함께 3단계), 집계 테이블·물질화 뷰(008, 3단계), 재시작 정책·TLS·멀티 아키텍처(011, 4단계).
- Claude Code 등 에이전트를 게이트웨이로(브리프 §8, 012에서 미실행).

**보류 (사소하거나 필요가 생기면)**

- 가짜 공급처: `abort`의 ERROR 로그 두 줄, 흉내 범위(도구 호출·이미지·캐시 쓰기), 일반 응답 끊김 시나리오, 타이밍 상한 검사(005).
- 중계: 스트림 조각의 `"usage": null` 키, 쿼리 문자열 비전달, `RelayEngine` 300줄 분리(006·007). 클라이언트 중단 테스트의 CI 안정성은 세 번 녹색이라 관찰만.
- 요금표 가져오기의 행별 조회(004), Gradle configuration cache(001).
- 대시보드: 세션 끊김 알림과 틀린 토큰 오류가 함께 보임, `StrictMode`의 개발 모드 이중 요청, 요청 목록의 1280px 가로 스크롤, 키 관리·요금표 화면의 해시 쿼리 미사용(009·010). 복사 버튼과 단가 입력란의 일반 브라우저 확인은 사용자가 3000 포트에서 쓰는 동안 문제 보고가 없었으나 명시적 확인은 받지 않았다.
- 배포: JVM 이미지 400MB, 스모크가 남기는 `smoke-*` 프로젝트(011).

## 남은 문제

- 2단계 계획을 쓸 때 위 "2단계로 이관" 목록을 입력으로 쓴다. 특히 요금 정확도(캐시 쓰기·구간별 단가)와 끊긴 스트림의 부분 사용량은 비용 수치의 신뢰에 직접 닿는다.
- 로컬 Ollama 모델의 "비용 0"은 API 과금이 없다는 뜻이지 전력·GPU 비용이 0이라는 뜻은 아니다. 로컬 모델에 가상 단가를 매겨 비교하고 싶으면 요금표에 0이 아닌 값을 넣으면 된다.
- 생각하는 모델(Qwen3 등)은 TTFT가 생각이 끝난 시점이 된다. 게이트웨이의 TTFT 정의(첫 스트림 조각)는 그대로 두되, 2단계에서 `reasoning` 조각을 구분해 기록할지 정한다.
