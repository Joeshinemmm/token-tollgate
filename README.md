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

## 라이선스

[MIT](LICENSE)
