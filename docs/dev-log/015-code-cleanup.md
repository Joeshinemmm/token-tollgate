# 015 저장소 전체 코드 정리

- 분석·승인·정리·검증: 2026-11-13
- 브랜치: `feature/code-cleanup` (정리 커밋 다섯 개 + 이 기록 + 후속 테스트 수정 커밋 하나)
- 성격: 동작을 바꾸지 않는 정리다. 기능 추가, 버그 수정, 성능 조정은 하지 않았고 발견한 것은 아래 목록으로만 남겼다. 예외는 하나다. 실행되지 않던 테스트 두 개는 승인을 따로 받아 별도 `[fix]` 커밋으로 고쳤다(끝의 "후속 수정")

## 목표

저장소 전체(서버, 가짜 공급처, 대시보드, 테스트)에서 쓸모없는 코드와 중복을 걷어 내고 읽기 어려운 곳을 푼다. 공개 API, REST 경로와 응답 형식, DB 스키마와 쿼리 결과, 설정 키 이름, 의존성은 건드리지 않는다.

## 완료 기준

1. 모듈(패키지 묶음) 단위로 정리하고, 묶음마다 테스트를 돌린 뒤 통과하면 그 묶음만 커밋한다. 실패하면 되돌리고 원인을 적는다.
2. 삭제하기 전에 참조를 확인한다: 코드 검색, Spring 어노테이션과 빈 등록, Spring Data 메서드 이름, Jackson 직렬화 대상, 설정 파일, 테스트.
3. 테스트의 단언과 테스트 케이스는 줄이지 않는다. 실행되는 테스트 수가 정리 전과 같다.
4. 대시보드는 화면이 바뀌지 않는다.
5. 확신이 없는 것은 지우지 않고 보류 목록에 둔다. 버그로 보이는 것은 고치지 않고 목록에 둔다.

## 진행 방식

1. 분석(수정 없음): code-simplifier 에이전트 넷이 서버 두 묶음, 가짜 공급처와 테스트, 대시보드를 읽기 전용으로 살펴 후보를 냈다. 삭제·중복 주장은 코드와 검색으로 다시 확인했고, 기계 대조(미사용 import, CSS 클래스, export, 토큰)와 컴파일러 경고 수집을 따로 했다.
2. 승인: 후보를 묶음별로 보여 주고 승인을 받았다.
3. 정리: 묶음마다 승인된 항목만 적은 지시로 code-simplifier에 맡기고, diff를 검토한 뒤 테스트를 돌려 커밋했다. 에이전트는 커밋하지 않았다.

기계 대조에서 미사용 Kotlin import는 0건, 어떤 TSX도 쓰지 않는 CSS 클래스도 0건이었다(`alert-*`, `badge-*`는 템플릿 문자열로 만들어진다). 완전히 죽은 코드는 거의 없었고 대부분 중복과 가독성 문제였다.

## 묶음별 변경

| 커밋 | 묶음 | 지운 것 | 바꾼 것 |
|---|---|---|---|
| `bddefe4` | 서버 keys·security·admin·gateway | 프로젝트 이름과 키 이름에 따로 있던 같은 검증 코드, 중복 상수 `MAX_NAME_LENGTH` 둘 | 검증을 `keys/NameValidation.kt`의 `validateName` 하나로, `SecurityConfig`의 반복 설정 다섯 줄을 확장 함수로, `AdminErrorHandler`의 핸들러를 예외별 둘로, `ProtocolErrorWriter.isAnthropic`을 private으로 |
| `64b695c` | 서버 relay·usage | `RelayEngine.failed`의 결과가 같은 중복 분기와 `isUnreachable`·import 둘, 두 어댑터와 두 검사기의 중복 로직 | 공통 함수 넷(`relay/RelayJson.kt`: `stringOrNull`, `readRequestObject`, `requireModel`, `parsePlainOutcome`), `relayPlain` 추출, 자기 대입과 조건 하나짜리 `when` 정리, `/v1/messages` 리터럴을 한 곳으로, 낡은 주석 수정, `UsageQueries`의 자바 박싱 클래스 참조를 `javaObjectType`으로(컴파일러 경고 11건 해소) |
| `f1e0c92` | 가짜 공급처 | 모든 호출처가 값을 넘기는 기본 인자 둘, 테스트 `readSse`의 안 쓰는 `headers` 매개변수, 경로 리터럴 중복 | `Ids.random`·`FakeText.word`를 private으로, 오류 본문을 `FakeErrors.body`로, 테스트의 요청 조립을 `postJson`으로, 리터럴을 기존 상수로 |
| `6d2d549` | 서버 테스트 지원 코드 | `readSse`의 안 쓰는 `extraHeaders`, 두 파일의 중복 `requestIdOf`, `awaitRecord`의 도달 불가 코드와 `@Suppress`, 불필요한 `!!` 둘 | 기존 `json` 헬퍼와 `NEVER_SET` 상수 재사용, 낡은 주석 수정 |
| `83a6548` | 대시보드 | 전역 규칙과 값이 같은 CSS 선언 다섯(포커스 링 셋, `.num`의 tabular-nums, 선택된 세그먼트의 테두리 색), 밖에서 안 쓰는 `Client.send`, 두 화면의 중복 리듀서·토글·정규식·테스트 헬퍼 | 필터 파생값을 `useUsageFilters`로, 조회 조건 조립을 `usageQuery`로, 내역 기준 라벨을 `breakdowns.ts` 한 곳으로, 중첩 삼항 둘과 조건 사슬을 풀어 씀 |

전체로는 파일 54개, 추가 332줄·삭제 305줄이다. 줄 수는 줄지 않았다. 중복이 사라진 대신 공통 함수 파일과 그 설명 주석, 삼항을 푼 `switch`가 들어왔다. 이 작업의 결과는 "같은 로직이 한 곳에만 있다"이지 "코드가 짧아졌다"가 아니다.

## 결정과 이유

- 승인 목록에 없는 것은 값어치가 있어 보여도 하지 않았다. 예를 들어 `OpenAiStreamObserver.DONE`과 `UsageRecorder.save`는 밖에서 쓰지 않아 private으로 줄일 수 있지만 승인 받은 표에 없었다(보류 목록).
- 오류 경로의 판정 로직은 합치지 않았다. `RelayEngine`의 결과 판정 통합과 `StreamFailure.errorType` 제거는 줄 수는 줄지만 non-null 단언이 새로 필요하고, 틀리면 사용 기록의 결과 값이 달라진다.
- 응답 JSON의 모양이 바뀔 수 있는 통합은 하지 않았다. 관리 API 지표 DTO를 합치면 필드 순서가 달라질 수 있고, 인증 오류 본문을 DTO로 바꾸면 security가 admin 패키지에 의존하게 된다.
- 대시보드의 데이터 로딩 effect는 공용 훅으로 묶지 않았다. 요청 시점과 StrictMode에서의 이중 실행 동작이 달라질 수 있다. 순수 계산(필터 파생값, 조회 조건)만 공통화했다.
- `tokens.css`와 `base.css`는 손대지 않았다. 디자인 도구가 세트로 만든 파일이고 `dashboard/design.md`가 `tokens.css`를 기준으로 삼는다. 아직 쓰지 않는 컨트롤 상태 스타일(`aria-disabled`, `aria-busy`, `:disabled`)과 디자인 검사 경고에 대응해 둔 선언도 남겼다.
- 서로 파일이 겹치지 않는 서버 첫 묶음과 대시보드는 에이전트를 동시에 돌렸고, 테스트와 커밋은 묶음별로 따로 했다. 가짜 공급처와 서버 테스트 코드는 서버 테스트가 가짜 공급처를 물고 있어 차례로 했다.
- 대시보드의 "화면이 같다"는 캡처로 확인했다. 정리 전 이미지와 정리 후 다시 빌드한 이미지를 같은 데이터로 띄워 13장을 찍고 파일 해시를 비교했다.

## 겪은 문제

1. Docker Desktop이 내려가 있어 샌드박스 밖에서 띄웠다.
2. 실패해서 되돌린 묶음은 없다. 다섯 묶음 모두 첫 테스트 실행에서 통과했다.

## 실행한 검증과 결과 (2026-11-13)

| 검증 | 결과 |
|---|---|
| 묶음별 서버 테스트(`./gradlew :server:test`, 묶음 ①②③④ 각각) | 매번 141개 통과, 실패 0 |
| 가짜 공급처 테스트(`./gradlew :fake-provider:test`) | 40개 통과 |
| 대시보드 `npm run lint`, `npm test`, `npm run build` | 경고 0, 28개 통과, 빌드 성공 |
| 대시보드 디자인 정적 검사(`sloplint --scope app`) | 필수 위반 0, 경고 0, 답변 1(013과 같음) |
| 대시보드 화면 전후 비교 | 13장(개요·요청 목록·키 관리·요금표·로그인, 밝은·어두운 모드, 1440·1280·375px) 모두 sha256 일치 |
| 최종 상태 전체 재실행(`./gradlew test --rerun-tasks`) | 서버 141, 가짜 공급처 40, 컴파일러 경고 0 |
| 최종 상태 스모크(`bash scripts/smoke.sh`, 이미지 재빌드) | 13단계 PASS |

실행된 테스트 수는 정리 전과 같다(서버 141, 가짜 공급처 40, 대시보드 28). 측정 조건: Windows 11 Home, JDK Temurin 25, Gradle 9.8.2, Docker Desktop 엔진 29.9.2, Chrome 156(캡처).

## 보류 목록 (지우거나 바꾸지 않은 것)

**서버**
- 인증 오류 본문(`security/EntryPoints.kt`)을 `AdminError` DTO로 바꾸는 안: security → admin 의존이 생긴다.
- `admin/UsageDtos.kt`의 지표 필드 여덟 개 중복: 합치면 JSON 모양이 바뀔 수 있다.
- `RelayEngine`의 결과 판정 통합, `StreamFailure.errorType` 제거, 300줄 분리(012의 보류 항목).
- `relay/anthropic/AnthropicErrors.kt`의 `529` 분기: 인자가 `HttpStatus`라 도달하지 않지만 문서의 표를 옮긴 것이다.
- `pricing/PriceSpec.kt`와 `usage/UsageQuery.kt`의 날짜 파싱: 비슷해 보이지만 시간대와 오류 메시지가 다르다.
- 승인 목록에 없던 가시성 축소: `OpenAiStreamObserver.DONE`, `UsageRecorder.save`, `KeyGenerator`의 상수 셋. `VirtualKeyService.revoke`의 쓰이지 않는 반환값.
- `relay/ProtocolAdapter.kt`의 `InspectedRequest`: `ByteArray`를 가진 data class라 `equals`가 내용을 비교하지 않는다. 지금은 비교에 쓰이지 않는다.

**가짜 공급처와 테스트**
- 두 가짜 컨트롤러의 골격이 비슷하다. 프로토콜별 인자가 달라 합치면 더 읽기 어렵다.
- 서버 테스트와 가짜 공급처 테스트의 지원 코드(`chatBody`/`openAiBody`, `SseResult`, `readSse` 등)가 겹친다. 공유하려면 test-fixtures 같은 빌드 설정이 필요하다.
- 승인 목록에 없던 것: `FakeUpstreamInitializer`의 index 처리, `RepositoryTest` 끝의 `Unit` 줄, `RelayTestSupport` 안 헬퍼 위치, 서버 테스트에 남은 리터럴(`AnthropicRelayTest`의 `"request-id"`, `GatewayAuthTest`의 `"/v1/messages"`).

**대시보드**
- `tokens.css`의 참조되지 않는 토큰 32개, 어두운 모드 값이 두 블록에 반복되는 것, `base.css`의 쓰이지 않는 규칙: 생성 파일이다.
- 아직 쓰지 않는 컨트롤 상태 스타일과 `.stat-lead .stat-value`의 tabular-nums(디자인 검사 대응).
- 데이터 로딩 effect의 공용 훅(같은 취소 패턴이 일곱 번 나온다).
- 파일 밖에서 쓰지 않는 export 열두 개, 인라인 `fontSize: 13` 네 곳, CSS 규칙이 없는 `ledger` 클래스, `DailyChart`의 격자 색 중복, `nginx.conf`의 프록시 헤더 중복.

## 버그 의심 지점 (1번만 후속 수정, 나머지는 수정하지 않음)

**직접 확인한 것**
1. (후속 수정에서 고침) 실행되지 않는 테스트가 둘 있었다. `VirtualKeyAdminApiTest`의 "issuing returns the plaintext once and stores only its hash"와 `GatewayAuthTest`의 "first use records last_used_at without blocking the request"다. `= runBlocking { … }`의 마지막 식이 값을 돌려줘 메서드 반환형이 Unit이 아니고, JUnit은 그런 `@Test`를 건너뛴다. 테스트 리포트에서 각각 4개 중 3개, 7개 중 6개만 실행됐다. 006에서 `ModelsApiTest`에 같은 문제가 있어 `runBlocking<Unit>`으로 고친 적이 있다. 그래서 "서버 141개"에는 이 둘이 빠져 있다.
2. `usage/UsageQueryService.kt`의 `records`: 받은 행 수가 `limit`과 같으면 다음 커서를 채운다. 남은 행이 정확히 한 페이지 분량이면 "더 보기"가 빈 페이지를 한 번 받는다.
3. `fake-provider`의 `FakeTextTest`: `assertEquals(FakeText.content(25), FakeText.content(25))`는 같은 호출을 자기 자신과 비교해 아무것도 검증하지 않는다.
4. `keys/VirtualKeyService.kt`의 `issue`: 보관된 프로젝트 검사가 이름 검증보다 앞이라, 보관된 프로젝트에 빈 이름으로 발급을 요청하면 400이 아니라 409가 나온다. 의도인지 확인이 필요하다.

**에이전트가 보고했고 직접 재현하지 않은 것**
5. `relay/RelayEngine.kt`의 `drain`: `buffer.read`가 예외를 던지면 버퍼 해제가 호출되지 않는다.
6. `keys/VirtualKeyService.kt`의 `lastUseWritten` 맵이 줄지 않는다(003의 남은 문제에 이미 있다).
7. `relay/openai/ModelsController.kt`: 같은 모델 이름이 활성 공급처 둘의 요금표에 있으면 하나만 목록에 나온다.
8. 테스트의 Kotlin `assert()`(`AdminAuthTest`, `GatewayAuthTest`): JVM의 `-ea`에 기대므로 `assertTrue`가 더 확실하다.
9. 대시보드 `ProjectList`: 이름 변경에서 Enter 뒤 blur가 다시 오면 저장이 두 번 갈 수 있고, Escape 뒤 blur가 오면 취소했는데도 저장될 수 있다.
10. 대시보드 `CopyButton`: 요청 ID 복사가 실패해도 "선택된 키를 직접 복사하세요"라고 나온다.
11. 대시보드 `OverviewPage`: 일별 조회가 성공하면 다른 요청이 남긴 오류 알림까지 지운다.
12. 대시보드 `KeysPage`: 키 발급 창을 취소로 닫아도 키 목록을 다시 조회한다.
13. 접근성: `Modal`이 포커스를 창 안에 가두지 않고 닫은 뒤 되돌리지 않는다. `Segmented`가 `role="tablist"`인데 tabpanel 연결이 없다. `PriceTable`의 이력 버튼 aria-label에 모델 이름만 있어 공급처가 다른 같은 이름을 구분하지 못한다.

## 후속 수정: 실행되지 않던 테스트 두 개 (2026-11-13)

버그 의심 1을 정리 커밋과 섞지 않으려고 승인을 따로 받아 별도 `[fix]` 커밋으로 했다.

- 바꾼 것: 두 테스트의 선언을 `= runBlocking {`에서 `= runBlocking<Unit> {`으로 바꿨다. 본문과 단언은 그대로고, 운영 코드는 건드리지 않았다.
- 이유: 블록의 마지막 식이 값을 돌려주면 함수의 반환형이 Unit이 아니게 된다. `VirtualKeyAdminApiTest`는 `WebTestClient` 단언 체인이, `GatewayAuthTest`는 `assertNotNull`이 돌려주는 값이 마지막 식이었다. 형식 인자 `<Unit>`을 적으면 마지막 식이 무엇이든 반환형이 Unit으로 고정된다.
- 결과: 두 테스트가 처음으로 실행됐고 둘 다 통과했다. 한 번도 돈 적이 없던 테스트라 실패할 수도 있었는데, 키 발급 시 해시만 저장하는 동작과 첫 사용 때 `last_used_at`을 기록하는 동작이 테스트가 적어 둔 대로였다.

| 검증 | 결과 |
|---|---|
| 두 클래스만 실행(`./gradlew :server:test --tests …`) | `VirtualKeyAdminApiTest` 4개 중 4개, `GatewayAuthTest` 7개 중 7개 실행·통과(전에는 3개, 6개) |
| 전체 실행(`./gradlew test`) | 서버 143개 통과(전 141), 실패 0. 가짜 공급처는 바뀐 것이 없어 다시 돌지 않았다(직전 전체 실행의 리포트 40개) |
| 선언 수와 실행 수 대조 | 테스트 파일마다 `@Test` 개수와 리포트의 실행 수를 비교했다. 서버 143 = 143, 가짜 공급처 40 = 40, 건너뜀 0. 같은 문제가 남은 테스트는 없다 |

## 남은 문제

- 같은 모양(`= runBlocking {`)으로 선언된 테스트가 33개 더 있다. 지금은 마지막 식이 Unit이라 모두 실행되지만, 마지막 줄이 값을 돌려주게 바뀌면 실패 없이 조용히 빠진다. 선언을 `runBlocking<Unit>`으로 통일하거나 선언 수와 실행 수를 CI에서 대조하는 식의 방지 장치는 아직 없다.
- 보류 목록 중 테스트 지원 코드 공유와 `RelayEngine` 분리는 구조를 바꾸는 일이라 2단계 계획에서 필요할 때 다시 본다.
- 이 브랜치는 push하지 않았다.
