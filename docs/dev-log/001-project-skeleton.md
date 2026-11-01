# 001. 프로젝트 골격

- 날짜: 2026-11-01
- 계획: `000-phase1-plan.md` 5장 작업 001

## 목표

서버·가짜 공급처·대시보드의 빌드와 테스트가 도는 상태를 만든다. DB·Flyway·Testcontainers는 002로 미룬다. DB 설정 없이는 앱이 뜨지 않아 "빌드가 도는 상태"라는 완료 기준이 흐려지기 때문이다.

## 완료 기준

1. `./gradlew build`가 성공하고 두 모듈의 health 테스트가 통과한다.
2. 두 앱을 실행하면 `/actuator/health`가 `UP`을 돌려준다(서버 8080, 가짜 공급처 9090).
3. `dashboard`에서 `npm run lint`와 `npm run build`가 성공한다.

## 만든 것

| 파일 | 내용 |
|---|---|
| `settings.gradle.kts`, `build.gradle.kts` | 루트 이름 `token-tollgate`, 모듈 `server`·`fake-provider`. 루트에서 플러그인 버전만 선언(Kotlin 2.3.21, Spring Boot 4.1.2, dependency-management 1.1.7)하고 하위 모듈 공통 설정(Java 25 toolchain, `-Xjsr305=strict`, `-Xannotation-default-target=param-property`, JUnit Platform) |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/*` | start.spring.io(Boot 4.1.2, Kotlin, Java 25)가 생성한 Wrapper 9.8.2 그대로 |
| `server/` | `TokenTollgateApplication`, `application.yml`(포트 `SERVER_PORT` 기본 8080, actuator health만 노출), `HealthEndpointTest` |
| `fake-provider/` | `FakeProviderApplication`, `application.yml`(포트 `FAKE_PROVIDER_PORT` 기본 9090), `HealthEndpointTest` |
| `dashboard/` | `create-vite@9.3.3 --template react-ts` 생성본. React 19.3.1, Vite 8.3.5, TypeScript 7.0.2, @vitejs/plugin-react 6.1.2, oxlint 1.90.0. `package-lock.json` 포함 |
| `.gitignore`, `LICENSE`(MIT), `README.md` | |

의존성(모두 Spring Boot BOM 관리 버전, start.spring.io 기본 구성에서 DB 관련만 뺌):
- server: `spring-boot-starter-webflux`, `spring-boot-starter-actuator`, `reactor-kotlin-extensions`, `kotlin-reflect`, `kotlinx-coroutines-reactor`, `jackson-module-kotlin`(Jackson 3, `tools.jackson`). 테스트: `spring-boot-starter-webflux-test`, `spring-boot-starter-actuator-test`, `kotlin-test-junit5`, `kotlinx-coroutines-test`, `junit-platform-launcher`
- fake-provider: 위에서 `reactor-kotlin-extensions`, `kotlinx-coroutines-test`를 뺀 구성

## 결정과 이유

- 루트 패키지는 `tokentollgate`(역도메인 없음). 소유한 도메인이 없고 사용자명을 패키지에 넣지 않기 위해서다.
- 가짜 공급처에도 actuator를 넣어 health 확인 방식을 서버와 통일했다.
- MIT 라이선스의 저작권자는 개인 정보를 피해 `token-tollgate contributors`로 적었다.
- 포트는 환경 변수(`SERVER_PORT`, `FAKE_PROVIDER_PORT`)로 바꿀 수 있게 해 compose에서 그대로 쓴다.

## 겪은 문제

- **`WebTestClient` 빈이 없어 테스트 실패.** 첫 빌드에서 `NoSuchBeanDefinitionException: No qualifying bean of type WebTestClient`. Spring Boot 4는 테스트 지원이 모듈로 나뉘어(`spring-boot-webtestclient`), `@SpringBootTest(webEnvironment = RANDOM_PORT)`만으로는 `WebTestClient`가 자동 구성되지 않는다. 공식 테스트 문서의 "spring-webflux가 classpath에 있으면 `@AutoConfigureWebTestClient`를 붙여 WebTestClient를 주입받을 수 있다"는 설명대로 `org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient`를 두 테스트에 추가해 해결했다. 애너테이션 패키지는 `spring-boot-webtestclient-4.1.2.jar` 내용으로 확인했다.
- 옛 폴더 `C:\dev\tollgate`는 파일을 모두 지웠지만 빈 디렉터리가 다른 프로세스에 잡혀 남아 있다. 앱 재시작 후 지우면 된다.

## 실행한 검증과 결과

| 검증 | 명령 | 결과 |
|---|---|---|
| 빌드·테스트 | `./gradlew build` | BUILD SUCCESSFUL (두 번째 실행 20초, 첫 실행은 Gradle 9.8.2 배포판과 의존성 내려받기 포함 53초). `tokentollgate.HealthEndpointTest` 1/1 통과, `tokentollgate.fake.HealthEndpointTest` 1/1 통과 |
| 실행 확인 | `java -jar server-0.1.0-SNAPSHOT.jar`, `java -jar fake-provider-0.1.0-SNAPSHOT.jar` 뒤 `GET /actuator/health` | 8080, 9090 모두 `{"groups":["liveness","readiness"],"status":"UP"}`. 로그에 "Netty started on port 8080/9090" |
| 대시보드 | `npm install`, `npm run lint`, `npm run build` | 28개 패키지 설치, oxlint 종료 코드 0(경고 없음), `tsc -b && vite build` 성공(20 modules, 102ms) |
| 경고 점검 | 두 빌드 로그에서 `warn`, `deprecat` 검색 | Kotlin·Gradle 경고 없음. 테스트 JVM의 "Sharing is only supported for boot loader classes" 메시지만 있는데, 이는 Byte Buddy 에이전트가 부트 classpath를 덧붙여 나오는 JVM CDS 안내이고 동작에 영향 없음 |

측정 조건: Windows 11 Home, JDK Temurin 25.0.5+8, Gradle 9.8.2, Node 26.13.0, 2026-11-01.

## 남은 문제

- 아직 커밋이 없다. 이 골격과 문서를 첫 커밋으로 남길지 사용자 확인 필요.
- Gradle이 configuration cache 활성화를 권한다. 빌드 시간이 문제가 될 때 검토한다.
- `dashboard/`는 템플릿 그대로다. 화면은 009에서 만든다.
