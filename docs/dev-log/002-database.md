# 002. DB 기반

- 날짜: 2026-11-01
- 브랜치: `feature/002-database`
- 계획: `000-phase1-plan.md` 4.3절, 5장 작업 002

## 목표

PostgreSQL 스키마를 Flyway로 관리하고, 서버가 R2DBC로 연결하며, Testcontainers로 실제 PostgreSQL 위에서 통합 테스트가 도는 기반을 만든다.

## 완료 기준

1. `./gradlew build`에서 Testcontainers PostgreSQL 위의 통합 테스트가 통과한다.
2. compose로 띄운 PostgreSQL에 서버를 연결하면 Flyway가 V1을 적용하고 health가 `r2dbc: UP`을 보인다.
3. `./gradlew :server:bootTestRun`으로도 같은 결과가 나온다.

## 만든 것

| 파일 | 내용 |
|---|---|
| `server/build.gradle.kts` | data-r2dbc, flyway(+`flyway-database-postgresql`, `spring-jdbc`), 런타임 `postgresql`(JDBC)·`r2dbc-postgresql`, 테스트용 data-r2dbc-test, flyway-test, `spring-boot-testcontainers`, testcontainers junit-jupiter·postgresql·r2dbc. 모두 Boot 4.1.2 BOM 관리 버전 |
| `server/src/main/resources/application.yml` | `tollgate.db.*`(환경 변수 `TOLLGATE_DB_HOST`, `TOLLGATE_DB_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`)에서 `spring.r2dbc.url`과 `spring.flyway.url`을 조합. health에 구성 요소 표시 |
| `server/src/main/resources/db/migration/V1__init.sql` | `projects`, `virtual_keys`, `model_prices`, `usage_records`. enum 성격 컬럼은 text + CHECK, uuid는 `gen_random_uuid()`, 시각은 `timestamptz` |
| `server/src/main/kotlin/tokentollgate/db/` | 엔티티 4개(`Entities.kt`), enum 3개(`Enums.kt`), enum↔text 변환기와 `R2dbcCustomConversions` 빈(`R2dbcConfig.kt`), `CoroutineCrudRepository` 4개(`Repositories.kt`) |
| `server/src/test/kotlin/tokentollgate/TestcontainersConfiguration.kt` | `@ServiceConnection PostgreSQLContainer("postgres:18")` 빈 |
| `server/src/test/kotlin/tokentollgate/TestTokenTollgateApplication.kt` | `bootTestRun` 진입점 |
| `server/src/test/kotlin/tokentollgate/db/SchemaMigrationTest.kt` | Flyway 이력 1건, 테이블 4개 존재 |
| `server/src/test/kotlin/tokentollgate/db/RepositoryTest.kt` | 프로젝트→키→요금→사용량 왕복, enum이 text로 저장·복원, `key_hash` 중복 거부, CHECK 위반 거부 |
| `HealthEndpointTest.kt` | DB 포함 health가 `UP`이고 `components.r2dbc.status == UP` |
| `docker-compose.yml`, `.env.example` | 로컬 실행용 `postgres:18` 서비스(볼륨 `pgdata`, healthcheck). 나머지 서비스는 011에서 추가 |

## 결정과 이유

- **Flyway는 JDBC로 돌린다.** `spring.flyway.url`만 주면 Boot 4.1.2의 `FlywayDataSourceCondition`이 활성화된다(000에서 소스로 확인). 테스트에서는 `@ServiceConnection`이 컨테이너에서 JDBC·R2DBC 연결 정보를 모두 만들어 이 속성을 덮어쓴다. 실제 동작을 이번 테스트와 bootTestRun에서 확인했다.
- **enum 컬럼은 text + CHECK.** PostgreSQL enum 타입을 쓰지 않아 마이그레이션이 단순하다. Kotlin enum은 대문자 상수에 `dbValue`를 두고, `DbEnum` 인터페이스 하나로 쓰기 변환기 1개와 읽기 `ConverterFactory` 1개만 등록했다. 저장값이 소문자 text임을 RepositoryTest가 raw SQL로 확인한다.
- **시각은 `Instant`, 마이크로초로 잘라서 저장.** 처음에는 `OffsetDateTime`을 썼는데 JVM(100ns 단위)과 PostgreSQL(마이크로초 단위)의 정밀도 차이로 왕복 후 값이 달라져 테스트가 실패했다. 엔티티 기본값을 `dbNow()`(= `Instant.now().truncatedTo(MICROS)`)로 바꿔 왕복이 정확히 같도록 했다. `Instant`가 r2dbc-postgresql의 timestamptz와 바로 매핑되는 것도 이 테스트로 확인했다.
- **uuid와 bigserial id는 DB가 생성.** 엔티티 id를 null로 저장하면 Spring Data R2DBC가 id 컬럼을 INSERT에서 빼고 RETURNING으로 받아 온다. 테스트에서 저장 후 id가 채워짐을 확인했다.
- **compose의 PostgreSQL 볼륨 경로는 `/var/lib/postgresql`.** postgres 18 공식 이미지부터 데이터 디렉터리 마운트 지점이 바뀌었다. 실제로 기동·healthy를 확인했다.
- 기본 DB 계정(`tollgate`/`tollgate`)은 로컬 개발 편의를 위한 값이고 `.env.example`에 "외부 노출 시 변경"을 적었다. 공급처 키·관리자 토큰은 기본값을 두지 않는다(003 이후).

## 실행한 검증과 결과

| 검증 | 명령 | 결과 |
|---|---|---|
| 빌드·테스트 | `./gradlew build` | BUILD SUCCESSFUL 18초(이미지 캐시 후). server 6개 테스트(RepositoryTest 3, SchemaMigrationTest 2, HealthEndpointTest 1) + fake-provider 1개 모두 통과. 첫 실행은 `postgres:18` 이미지 내려받기 포함 45초, HealthEndpointTest 25초 |
| compose + jar 실행 | `docker compose up -d --wait postgres` → `java -jar server-0.1.0-SNAPSHOT.jar` | postgres healthy. 로그: "Migrating schema public to version 1 - init", "Successfully applied 1 migration". health: `status UP`, `components.r2dbc UP` |
| 재시작 | 같은 jar 다시 실행 | "Current version of schema public: 1", "Schema public is up to date. No migration necessary." |
| bootTestRun | `./gradlew :server:bootTestRun` | 9초 만에 health UP. 로그: "Container postgres:18 started in PT1.88S", "Successfully applied 1 migration". 앱을 종료하자 Ryuk가 컨테이너를 정리함 |

측정 조건: Windows 11 Home, Docker Desktop 4.97.0(WSL2), JDK Temurin 25.0.5+8, Gradle 9.8.2, 2026-11-01.

## 남은 문제

- compose의 `postgres` 서비스는 호스트 5432를 노출한다. 011에서 전체 compose를 만들 때 노출 여부를 다시 정한다.
- `GET /actuator/health`가 구성 요소까지 보여 준다. 공개 환경에서는 `show-components`를 끄거나 actuator를 내부망으로 제한해야 한다(011에서 결정).
- dev-log 000의 "R2DBC와 Flyway" 확인 필요 항목은 이번 실행으로 해소됐다.
