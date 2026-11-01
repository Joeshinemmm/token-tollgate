# 003. 가상 키와 인증

- 날짜: 2026-11-01
- 브랜치: `feature/003-virtual-keys-auth`
- 계획: `000-phase1-plan.md` 4.2·4.4절, 5장 작업 003

## 목표

프로젝트와 가상 키를 관리 API로 발급·폐기하고, `/v1/**`는 가상 키로, `/admin/**`는 관리자 토큰으로 인증한다. 006·007의 중계는 여기서 만든 인증 결과(`AuthenticatedKey`)를 그대로 쓴다.

## 완료 기준

1. 발급한 키로 `/v1/**`를 통과하고, 없는 키·폐기된 키·보관된 프로젝트의 키는 401이며 본문이 경로별 프로토콜 형식이다.
2. 관리 API가 토큰 없이는 401, 토큰이 있으면 프로젝트·키 CRUD가 된다.
3. 키 평문은 발급 응답에만 있고 DB에는 SHA-256 해시만 있다.
4. 관리자 토큰이 없으면 서버가 기동하지 않는다.

## 만든 것

| 파일 | 내용 |
|---|---|
| `server/build.gradle.kts` | `spring-boot-starter-security` 추가(Boot 4.1.2 BOM, Spring Security 7.1.2) |
| `security/SecurityConfig.kt` | `SecurityWebFilterChain` 3개. `/admin/**`(관리자 토큰), `/v1/**`(가상 키), 나머지(health만 허용, 그 외 거부). 세션·CSRF·Basic·form 로그인 모두 끔 |
| `security/AuthenticationManagers.kt` | `ReactiveAuthenticationManager` 빈 하나가 자격 증명 종류로 분기. 관리자 토큰은 `MessageDigest.isEqual`로 상수 시간 비교 |
| `security/Converters.kt` | 헤더 → 자격 증명. 관리자는 `Authorization: Bearer`, 가상 키는 Bearer 우선·`x-api-key` 차선, `X-Tollgate-Tag`(100자 초과면 400) |
| `security/EntryPoints.kt` | 인증 실패 응답. `/admin`은 `{"error":{"code":"unauthorized",...}}`, `/v1`은 경로에 따라 OpenAI·Anthropic 형식 |
| `security/Authentications.kt`, `AdminProperties.kt` | `AuthenticatedKey`(keyId, projectId, keyPrefix, tag)와 토큰 타입, `tollgate.admin.token` 바인딩(비어 있거나 16자 미만이면 기동 실패) |
| `gateway/ProtocolErrorWriter.kt` | OpenAI·Anthropic 형식 오류 JSON 작성기. 006·007에서 재사용 |
| `keys/KeyGenerator.kt` | `tt_` + base62 32자, 표시용 앞 8자, SHA-256 hex |
| `keys/ProjectService.kt`, `VirtualKeyService.kt` | 프로젝트 생성·조회·보관, 키 발급·목록·폐기(멱등)·인증 조회, `last_used_at` 조절 갱신 |
| `db/Repositories.kt` | `findByName`, 정렬 조회, 키+프로젝트 보관 여부를 한 번에 읽는 `@Query`(`KeyAuthView`), `last_used_at` 갱신 `@Modifying @Query` |
| `admin/ProjectController.kt`, `VirtualKeyController.kt`, `AdminDtos.kt`, `AdminErrorHandler.kt` | 관리 API와 오류 형식 `{"error":{"code","message"}}` |
| `common/Exceptions.kt` | `NotFoundException`(404), `ConflictException`(409), `BadRequestException`(400) |
| `.env.example`, `README.md` | `TOLLGATE_ADMIN_TOKEN` 안내 |
| 테스트 | `IntegrationTest` 메타 애너테이션(한 컨텍스트 공유), `TestSupport.kt`(관리 API 헬퍼), `AuthProbeController`(테스트 전용 `/v1/auth-probe`), `KeyGeneratorTest`, `AdminAuthTest`, `ProjectAdminApiTest`, `VirtualKeyAdminApiTest`, `GatewayAuthTest`. 기존 DB·health 테스트도 메타 애너테이션으로 정리 |

관리 API 요약(`/admin/v1`, `Authorization: Bearer <TOLLGATE_ADMIN_TOKEN>`):
`POST/GET /projects`, `GET/PATCH /projects/{id}`(name, archived), `POST /projects/{id}/keys`(평문 1회 반환), `GET /keys?projectId=`, `DELETE /keys/{id}`(폐기, 멱등).

## 결정과 이유

- **Spring Security(WebFlux)로 전환.** 계획 000과 명세 초안은 WebFilter 두 개였으나, 사용자가 Spring Security 사용을 택했다. 구성은 `AuthenticationWebFilter` + `ServerAuthenticationConverter` + 하나의 `ReactiveAuthenticationManager` + `ServerAuthenticationEntryPoint`로, 람다 DSL만 유효한 7.x 규칙을 따랐다(공식 레퍼런스와 7.0 Javadoc으로 확인). 관리자 토큰 비교와 키 해시 조회 같은 핵심 안전 장치는 어느 쪽으로 해도 같다.
- **`ReactiveAuthenticationManager` 빈을 하나만 둔다.** 두 체인이 같은 빈을 쓰고 자격 증명 타입으로 분기한다. 빈이 둘이면 Boot의 `ServerHttpSecurity` 자동 설정이 주입 대상을 고르지 못하고, 빈이 없으면 Boot가 기본 사용자와 생성 비밀번호를 만든다. 실행 로그에 "generated security password"가 없음을 확인했다.
- **기본 체인은 거부가 기본값.** `/actuator/health`만 허용하고 나머지는 모두 거부한다. 새 엔드포인트를 추가하면서 인증을 빠뜨리는 실수를 막기 위해서다. 그 대가로 없는 경로가 404 대신 401이 된다.
- **상태 없는 체인.** 모든 체인에 `NoOpServerSecurityContextRepository`를 써서 세션을 만들지 않는다. CSRF·Basic·form·logout은 끈다.
- **관리자 토큰은 환경 변수에서만, 기본값 없음.** Boot의 느슨한 바인딩이 `TOLLGATE_ADMIN_TOKEN`을 `tollgate.admin.token`으로 읽는다. yml에 `${TOLLGATE_ADMIN_TOKEN}` 자리표시자를 쓰면 미해결 시 그 문자열 자체가 토큰이 될 수 있어 쓰지 않았다. 16자 미만도 거부한다.
- **키 형식 base62.** `+`, `/`, `=`가 없어 복사·URL·셸에서 안전하고, 주요 서비스의 키 형식(접두사 + 영숫자)과 같다. 32자는 약 190비트다.
- **`last_used_at`은 키당 60초에 한 번만 기록.** 요청마다 쓰지 않도록 메모리 맵으로 조절하고, 비동기로 쓰며 실패는 경고 로그만 남긴다.
- **태그 길이 초과는 400.** 변환기에서 `AuthenticationException` 하위 예외를 던지고 진입점이 400으로 바꾼다. `AuthenticationWebFilter`가 변환기 예외도 실패 처리기로 보내는 것을 테스트로 확인했다.
- **입력 검증은 서비스에서 직접.** 필드가 `name` 하나라 `spring-boot-starter-validation`은 더하지 않았다. `spring-boot-starter-security-test`도 헤더 기반 테스트에는 필요 없어 더하지 않았다.

## 겪은 문제

- **KDoc 안의 `/admin/**`가 주석을 중첩해서 열었다.** Kotlin은 블록 주석이 중첩되므로 주석 속 `/**`가 새 주석을 연다. 주석의 경로 표기를 `/admin/...`으로 바꿨다.
- **테스트 클라이언트가 앞뒤 공백이 있는 헤더 값을 거부했다.** `X-Tollgate-Tag: "  nightly-build "`를 보내자 Reactor Netty 클라이언트가 "Validation failed for header"로 요청 자체를 막았다. 서버의 trim 처리는 유지하고 테스트는 공백 없는 값을 보낸다.
- **PowerShell 5.1에서 curl.exe에 JSON을 넘기면 따옴표가 벗겨진다.** 수동 검증 1차 시도가 "Failed to read HTTP message"로 실패했다. 본문을 파일로 넘겨(`--data-binary @file`) 해결했다. 서버 문제가 아니다.

## 실행한 검증과 결과

| 검증 | 명령 | 결과 |
|---|---|---|
| 빌드·테스트 | `./gradlew build` | BUILD SUCCESSFUL 14초. server 29개 + fake-provider 1개 = 30개 통과. 통합 테스트 전부가 하나의 컨텍스트를 공유(첫 클래스만 7.6초) |
| 토큰 없는 기동 | compose postgres + `java -jar` (환경 변수 없음) | "APPLICATION FAILED TO START", "Reason: TOLLGATE_ADMIN_TOKEN must be set" |
| 기본 사용자 생성 여부 | 기동 로그 검색 | "generated security password" 없음 |
| 관리 API | curl: 토큰 없이 → 401 `unauthorized`; 프로젝트 생성 201; 같은 이름 409 `conflict`; 키 발급 201(`tt_` 35자, 응답에만 평문); 폐기 204·204; 목록에 평문 없고 `revokedAt`·`lastUsedAt` 채워짐; 보관 PATCH 200 | 모두 명세대로 |
| 게이트웨이 인증 | curl: 유효 키로 `/v1/messages`·`/v1/chat/completions` → 404(라우트 없음, 인증은 통과); 키 없이 `/v1/messages` → 401 Anthropic 형식, `/v1/chat/completions` → 401 OpenAI 형식(`code: invalid_api_key`); 태그 101자 → 400 `invalid_request_error`; 폐기 키 → 401 "API key has been revoked"; 없는 키 → 401 "Invalid API key" | 모두 명세대로 |

측정 조건: Windows 11 Home, Docker Desktop 4.97.0, JDK Temurin 25.0.5+8, Gradle 9.8.2, 2026-11-01.

## 남은 문제

- 유효한 키로 아직 없는 라우트를 부르면 Boot 기본 404 JSON이 나온다. 006·007에서 라우트가 생기면 사라지고, 모르는 `/v1` 경로의 404도 프로토콜 형식으로 바꿀지 그때 정한다.
- `last_used_at` 조절용 메모리 맵은 키 수만큼 자란다. 1단계 규모에서는 문제없지만 3단계 레이트리밋을 Redis로 옮길 때 함께 옮길 후보다.
- 관리자 토큰이 하나뿐이라 회전하려면 재기동이 필요하다. 2단계 이후 사용자·역할이 생기면 재검토한다.
