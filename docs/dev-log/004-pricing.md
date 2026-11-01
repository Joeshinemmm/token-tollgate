# 004. 요금표와 비용 계산

- 날짜: 2026-11-01
- 브랜치: `feature/004-pricing`
- 계획: `000-phase1-plan.md` 4.2절(`pricing`)·4.3절(`model_prices`, 요금표 초기값)·4.4절(요금표 API), 5장 작업 004

## 목표

모델별 단가를 DB에서 관리하고, (공급처, 모델, 요청 시각, 토큰 수)로부터 요청 하나의 비용을 계산하는 서비스를 만든다. 006·007의 중계가 이 서비스를 호출해 `usage_records.cost`와 `price_id`를 채운다.

## 완료 기준

1. 단가 유무·캐시 토큰 유무·캐시 단가 유무·반올림 케이스의 비용 계산 단위 테스트 통과. (통과)
2. 관리 API로 단가 조회·upsert·삭제가 되고, 검증 실패는 400, 사용 기록이 참조하는 단가 삭제는 409. (통과)
3. `TOLLGATE_PRICES_FILE`로 지정한 YAML의 단가가 시작 시 들어가고, 재시작해도 중복되지 않는다. (통과)
4. 예시 요금표의 값이 공식 가격 페이지 기준이며, 확인한 URL과 날짜가 파일과 이 기록에 있다. (아래 "확인한 공식 문서")

## 만든 것

| 파일 | 내용 |
|---|---|
| `server/build.gradle.kts` | `tools.jackson.dataformat:jackson-dataformat-yaml` 추가(버전은 Boot 4.1.2가 들여오는 Jackson BOM 3.1.7이 관리). `bootRun`의 작업 디렉터리를 저장소 루트로 지정 |
| `pricing/CostCalculator.kt` | `TokenUsage(input, output, cached)`와 순수 함수 `cost(price, usage)`. 100만 토큰당 단가 × 토큰 수, 소수 8자리 HALF_UP |
| `pricing/PriceSpec.kt` | 관리 API와 파일이 공유하는 검증. 이름 trim·길이, 단가 0 이상·소수 8자리 이내·상한, 통화 USD만, `effectiveFrom`은 날짜(UTC 자정) 또는 ISO-8601 instant |
| `pricing/PriceFileParser.kt` | YAML → `PriceSpec` 목록. snake_case 키, 모르는 키는 오류, 항목별 오류에 `prices[i]:` 접두사 |
| `pricing/PricingService.kt` | `priceFor`(요청 시각 이하 가장 최근 `effective_from`), `cost` → `Priced(cost, priceId)` 또는 `Unpriced`, 목록·upsert·삭제·파일 가져오기 |
| `pricing/PriceSeeder.kt`, `PricingProperties.kt` | `tollgate.prices.file`(환경 변수 `TOLLGATE_PRICES_FILE`)이 있으면 시작 직후 가져오고 삽입·건너뜀 수를 로그 |
| `db/Repositories.kt` | `ModelPriceRepository`: (provider, model, effective_from) 조회, 정렬 목록, 유효 단가 `@Query`(`effective_from <= :at ORDER BY effective_from DESC LIMIT 1`) |
| `admin/PriceController.kt`, `AdminDtos.kt` | `GET /admin/v1/prices?provider&model`, `PUT /admin/v1/prices`(새 행 201, 기존 행 단가 교체 200), `DELETE /admin/v1/prices/{id}`(204, 없으면 404, 사용 기록이 참조하면 409) |
| `application.yml` | `spring.jackson.write.write-bigdecimal-as-plain: true`. 0.00000015를 `1.5E-7`이 아니라 평문으로 출력 |
| `config/prices.example.yaml` | 예시 요금표 11행(OpenAI 5, Anthropic 4, 가짜 공급처 2). 출처 URL·확인일·담지 못하는 것 주석 |
| `.env.example`, `README.md` | `TOLLGATE_PRICES_FILE` 안내, 요금표 절과 비용 공식, `bootRun`은 `.env`를 읽지 않으므로 셸 환경 변수로 준다는 설명 |
| 테스트 | 단위 `CostCalculatorTest`(6), `PriceSpecTest`(3), `PriceFileParserTest`(5, 예시 파일 파싱 포함). 통합 `PricingServiceTest`(2), `PriceImportTest`(3), `PriceAdminApiTest`(4). 합계 23개 추가 |

## 확인한 공식 문서 (2026-11-01)

- OpenAI 가격: `https://platform.openai.com/docs/pricing`은 `https://developers.openai.com/api/docs/pricing`으로 301 리다이렉트된다. "Prices per 1M tokens". 표준(Standard) 탭, 짧은 문맥(입력 272K 토큰 이하) 열의 `gpt-6-astra` 10/1/50, `gpt-6.1-sol` 2/0.10/10, `gpt-6.1-terra` 1/0.05/5, `gpt-6-sol` 2/0.20/10, `gpt-6-luna` 0.10/0.01/0.50 (입력/캐시 입력/출력, USD per 1M). 긴 문맥(272K 초과)은 별도 열이고, Batch·Flex·Fast 탭은 다른 단가다. `gpt-5.6-sol`은 2026-11-21까지 판촉 단가라 예시에서 뺐다.
- Anthropic 가격: `https://docs.claude.com/en/docs/about-claude/pricing`은 `https://platform.claude.com/docs/en/about-claude/pricing`으로 302 리다이렉트된다. 표 열은 Base input tokens / 5m cache writes / 1h cache writes / Cache hits and refreshes / Output tokens. 캐시 쓰기는 기본 입력의 1.25배(5분)·2배(1시간), 캐시 읽기는 0.1배가 기본이나 Fable 5.1은 0.025배, Opus 5.5·Sonnet 5.5는 0.05배다. 예시에 담은 값: Fable 5.1 10/0.25/50, Opus 5.5 4/0.20/20, Sonnet 5.5 2/0.10/10, Haiku 5.5(프롬프트 100K 토큰 이하) 0.10/0.01/0.50. 가격 페이지에는 표시 이름만 있고 모델 id는 `https://platform.claude.com/docs/en/about-claude/models/overview`의 "Claude API ID" 행에서 가져왔다(`claude-fable-5-1`, `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5`).
- 캐시 읽기 배율이 모델마다 다르다는 점은, 단가를 배율이 아니라 `cached_input_per_mtok` 값으로 저장하는 설계가 맞았음을 뒷받침한다.
- Spring Boot 4.1.2 Jackson 설정: 레퍼런스의 "Customize the Jackson JsonMapper"에 `spring.jackson.<enum>.<feature_name>` 형식이 있고, 로컬 Gradle 캐시의 `spring-boot-jackson-4.1.2.jar`에서 `JacksonProperties.write`가 `Map<StreamWriteFeature, Boolean>`임을, `jackson-core-3.1.7.jar`에서 `StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN`이 있음을 `javap`로 확인했다. 그래서 `spring.jackson.write.write-bigdecimal-as-plain`이다.
- 의존성: `spring-boot-dependencies-4.1.2.pom`이 `tools.jackson:jackson-bom:3.1.7`을 들여오고, 그 BOM이 `jackson-dataformat-yaml`을 관리한다. `spring-boot-starter` 4.1.2는 `org.yaml:snakeyaml:2.6`을 이미 포함한다(대안 검토용).
- 토큰 필드 의미는 000 3장에서 확인한 것을 그대로 썼다. OpenAI `prompt_tokens`는 `prompt_tokens_details.cached_tokens`를 포함한 전체, Anthropic은 `input_tokens + cache_creation_input_tokens + cache_read_input_tokens`가 입력 전체.

## 결정과 이유

- **토큰 의미 규약.** `input_tokens`는 캐시 적중분을 포함한 전체 입력, `cached_input_tokens`는 그중 캐시에서 읽은 부분. OpenAI가 이 의미로 보고하고, 대시보드의 "입력 토큰" 합계가 자연스럽다. Anthropic은 007에서 세 값을 더해 맞춘다. 캐시 쓰기 토큰은 1단계에서 입력 단가로 계산한다(실제보다 1.25~2배 적게 잡힘, 남은 문제).
- **비용 공식과 반올림.** `((input − cached) × 입력단가 + cached × (캐시단가 ?: 입력단가) + output × 출력단가) ÷ 1,000,000`, 소수 8자리 HALF_UP. 8자리는 `usage_records.cost numeric(18,8)`과 같다. 단가는 소수 8자리까지라 토큰 수를 곱하고 10^6으로 나누면 최대 14자리가 나오므로 반올림이 필요하다.
- **계산기는 예외를 던지지 않는다.** 음수는 0으로, `cached > input`은 `input`으로 자른다. 사용량 파싱 버그가 있어도 기록 자체는 남겨야 하기 때문이다.
- **단가 캐시 없음(계획과 다름).** 요청당 한 번, 비동기 기록 경로에서 유니크 인덱스를 타는 점 조회다. 측정 없이 캐시를 두면 갱신 타이밍 버그만 생긴다. 필요해지면 측정값과 함께 넣는다.
- **파일 가져오기는 "없는 행만 추가"(계획과 다름).** 빈 테이블이면 전부 들어가므로 원래 조건을 포함하고, 모델을 추가해 재시작하면 반영되며, 관리 API로 고친 행은 덮어쓰지 않는다. 파일 전체를 먼저 파싱·검증하고 나서 쓰므로 잘못된 파일은 아무것도 넣지 않는다. 잘못된 파일은 기동 실패다. 명시적으로 지정한 파일이 틀렸는데 조용히 넘어가면 비용이 모두 비게 된다.
- **단가 변경은 행 추가.** `effective_from`이 더 늦은 행을 넣으면 그 시각부터 적용되고 과거 기록은 당시 단가로 남는다. PUT은 같은 (provider, model, effective_from)의 단가를 교체하는 용도(오타 수정)다. 사용 기록이 참조하는 행은 FK 때문에 지울 수 없고 409로 알려 준다.
- **`effective_from` 입력 형식.** `YYYY-MM-DD`(UTC 자정)와 ISO-8601 instant 둘 다 받는다. 사람이 쓰는 파일에는 날짜가, 대시보드·API에는 instant가 편하다. 예시 파일의 실제 공급처 행은 가격을 확인한 날짜(2026-11-01)를 적었다. 공급처 페이지에 적용 시작일이 없어 확인일이 가장 정직한 값이다.
- **YAML은 Jackson 모듈로.** 기존 `jackson-module-kotlin`과 같은 방식으로 데이터 클래스에 바인딩하고, `FAIL_ON_UNKNOWN_PROPERTIES`를 켜서 키 오타가 조용히 단가를 빠뜨리지 못하게 했다(Jackson 3는 이 기능이 기본 꺼짐). snakeyaml 직접 사용은 Map 수동 변환이라 택하지 않았다.
- **`price_missing`은 파생.** 컬럼을 두지 않고 `cost IS NULL AND usage_source <> 'none'`으로 구한다. 마이그레이션 없이 같은 정보를 얻는다.
- **통화는 USD만.** 합산이 USD 하나로 이뤄지므로 다른 통화를 받으면 합계가 깨진다. 컬럼은 남겨 둔다.
- **구간별 단가는 담지 않는다.** OpenAI 긴 문맥(272K 초과), Haiku 5.5의 100K 초과 구간은 요청별 프롬프트 길이에 따라 단가가 달라지는데 1단계 요금표는 (provider, model) 하나에 단가 하나다. 짧은 구간 단가만 적고 파일 주석과 남은 문제에 남겼다.
- **`bootRun` 작업 디렉터리를 저장소 루트로.** Boot Gradle 플러그인의 기본 작업 디렉터리는 모듈 디렉터리(`server/`)라 README의 `config/prices.example.yaml` 같은 상대 경로가 어긋난다. `java -jar`와 `bootRun`이 같은 경로를 쓰도록 맞췄다.
- **README의 `.env` 설명 보강.** 003의 README는 `.env`를 채우고 `bootRun`하라고만 적었는데 Gradle은 `.env`를 읽지 않는다. 셸 환경 변수로 주라고 고쳤다.

## 겪은 문제

- **샌드박스 셸에서 띄운 Docker Desktop이 종료됐다.** 테스트를 돌리려는데 Docker 엔진이 없어 `Start-Process`로 Docker Desktop을 띄웠더니 "initializing Secrets Engine: rename engine.sock → engine.sock.stale: The file cannot be accessed by the system" 오류로 종료됐다. 샌드박스 안에서 만들어진 0바이트 소켓 파일을 일반 프로세스가 다루지 못한 것으로 보인다. 샌드박스 없이 다시 띄우자 정상 기동했다. 잔여 소켓 파일은 Windows가 삭제를 거부했지만 기동에 영향이 없어 두었다. 다음부터 Docker Desktop은 샌드박스 밖에서 띄운다.
- **PowerShell 검증 스크립트.** 함수 매개변수 이름을 `$args`로 지어 자동 변수와 겹쳤고(curl에 URL이 안 넘어감), 함수 안의 `Write-Output`이 반환값에 섞여 변수로 들어갔다. 표시용 출력을 `Write-Host`로 바꿨다. 서버 문제가 아니다.
- **긴 다중 heredoc 셸 명령이 통째로 파싱 실패했다.** 작은 heredoc은 문제없었다. 소스 파일은 파일 쓰기 도구로 썼다.

## 실행한 검증과 결과

| 검증 | 명령 | 결과 |
|---|---|---|
| 빌드·테스트 | `./gradlew build` | BUILD SUCCESSFUL 14초(컴파일은 최신 상태, 테스트만 실행). server 52개(004에서 23개 추가) + fake-provider 1개 = 53개 통과, 실패 0 |
| 시작 시 가져오기 | compose postgres + `java -jar` (저장소 루트, `TOLLGATE_PRICES_FILE=config/prices.example.yaml`) | 1회차 로그 "11 rows inserted, 0 already present", 2회차 "0 rows inserted, 11 already present", 환경 변수 없이 3회차는 가져오기 로그 없음 |
| `bootRun` 작업 디렉터리 | `gradlew :server:bootRun` + 같은 환경 변수 | 로그에 `C:\dev\token-tollgate\config\prices.example.yaml` 절대 경로, 11건 존재. PUT 새 행 201 → DELETE 204 |
| 관리 API | curl | `GET /admin/v1/prices` 200(예시 11행), `?provider=anthropic&model=claude-sonnet-5-5` 200(1행, 2/0.10/10), 토큰 없이 401, PUT 같은 키 200(단가 교체), `currency: EUR` 400 "currency must be USD", 음수 단가 400 "inputPerMtok must not be negative", DELETE 204, 재삭제 404 |
| 평문 소수 | PUT `inputPerMtok: 0.00000015` | 응답 본문 `"inputPerMtok":0.00000015` (지수 표기 없음). 통합 테스트에서도 확인 |
| 예시 파일 | `PriceFileParserTest` | 11행 파싱, provider 집합 {openai, anthropic, fake}, (provider, model, effective_from) 중복 없음 |

측정 조건: Windows 11 Home, Docker Desktop 4.97.0(엔진 29.9.2), JDK Temurin 25.0.5+8, Gradle 9.8.2, PostgreSQL 18(compose와 Testcontainers 모두), 2026-11-01.

## 남은 문제

- 프롬프트 길이 구간별 단가(OpenAI 272K 초과, Haiku 5.5 100K 초과)와 Anthropic 캐시 쓰기 단가(1.25배·2배)를 담지 못한다. 2단계에서 `cache_write_per_mtok`와 구간 열 추가를 검토한다. 그때까지는 긴 프롬프트와 캐시 쓰기가 실제보다 적게 잡힌다.
- 모델 이름은 정확히 일치해야 한다. 공급처가 응답에서 날짜가 붙은 id를 돌려주는 경우(`model_served`)의 별칭 처리는 006·007에서 `model_requested`·`model_served` 중 무엇으로 조회할지와 함께 정한다.
- 예시 요금표의 가격은 2026-11-01 기준이며 바뀐다. 사용 전 다시 확인해야 한다는 주석을 파일 첫머리에 두었다.
- `importFile`은 행마다 조회·삽입을 반복한다. 11행 규모라 문제없지만 수백 행이면 일괄 조회로 바꾼다.
