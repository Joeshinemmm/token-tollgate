# 아키텍처 다이어그램

token-tollgate의 전체 구조, 모듈 의존성, 핵심 흐름 다섯 개, DB 엔티티 관계를 코드에서 읽어 그린 다이어그램 모음이다.

- 생성 일자: 2026-11-15
- 기준: `main` 브랜치, 커밋 `58bf398e32f4bbdf58c7e7854031f9e6463bd882`
- 생성 방법: codemap 플러그인 1.1.0의 명령 `/codemap:architecture`, `/codemap:deps`, `/codemap:flow`(대상 다섯 개), `/codemap:er`를 차례로 실행했다. 각 명령의 절차대로 코드를 분석해 Mermaid를 쓰고 `@mermaid-js/mermaid-cli` 12.2.0으로 SVG를 렌더링했다. 렌더링 명령에는 설치된 Chrome을 쓰는 옵션(`-p`) 하나만 더했다. 렌더링에 성공한 Mermaid만 실었고, 아래 코드 블록은 SVG를 만든 원본과 같다.
- 이 문서는 위 커밋의 코드를 기준으로 한다. 코드가 바뀌면 다이어그램도 다시 만들어야 한다.

## 목록

| # | 다이어그램 | SVG | 보여 주는 것 |
|---|---|---|---|
| 1 | 전체 시스템 구조 | [architecture.svg](architecture.svg) | 컨테이너 넷과 서버 안의 계층, 상위 공급처 호출 |
| 2 | 모듈 의존성 | [deps.svg](deps.svg) | 패키지·디렉터리 단위 import 관계, 순환 여부 |
| 3 | 중계 요청 흐름 | [flow-post-v1-chat-completions.svg](flow-post-v1-chat-completions.svg) | `POST /v1/chat/completions`가 들어와 기록될 때까지 |
| 4 | 스트리밍 중계 | [flow-RelayEngine-streamUpstream.svg](flow-RelayEngine-streamUpstream.svg) | SSE 프레임 전달과 실패 판정 |
| 5 | 가상 키 인증 | [flow-VirtualKeyService-authenticate.svg](flow-VirtualKeyService-authenticate.svg) | `/v1/**` 요청의 키 검사와 거부 응답 |
| 6 | 사용 기록과 비용 계산 | [flow-UsageRecorder-save.svg](flow-UsageRecorder-save.svg) | 단가 조회, 비용 계산, `usage_records` 저장 |
| 7 | 사용량 요약 조회 | [flow-get-admin-v1-usage-summary.svg](flow-get-admin-v1-usage-summary.svg) | `GET /admin/v1/usage/summary`의 검증과 집계 |
| 8 | DB 엔티티 관계 | [er.svg](er.svg) | 테이블 넷과 외래 키 |

다이어그램 안의 `flow-...` 표기는 이 폴더의 다른 흐름도를 가리킨다.

## 1. 전체 시스템 구조

`docker compose`로 뜨는 컨테이너 넷(dashboard, server, fake-provider, postgres)과 server 안의 계층(보안 체인 → 컨트롤러 → 서비스 → 저장소), 상위 공급처로 나가는 호출을 보여 준다. 화살표 라벨은 통신 방식이나 호출 목적이다.

- 근거: `docker-compose.yml`, `dashboard/nginx.conf`, `server/src/main/resources/application.yml`, `server/src/main/kotlin/tokentollgate/security/SecurityConfig.kt`, 각 컨트롤러와 서비스의 생성자 매개변수.
- 포트와 호스트 이름은 `docker-compose.yml`과 `application.yml`의 기본값이다. `.env`로 바꾸면 달라진다.
- "API 클라이언트"와 "관리자 브라우저"는 코드에 없는 외부 사용자다(추정). `/actuator/health`는 실제로는 `server/Dockerfile`의 HEALTHCHECK와 `scripts/smoke.sh`가 호출한다.

SVG: [architecture.svg](architecture.svg)

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 600}}}%%
graph TD
    apiClient["API 클라이언트<br/>OpenAI·Anthropic SDK, curl"]
    browser["관리자 브라우저"]

    subgraph dashboardBox["dashboard 컨테이너 (nginx :80, 호스트 :3000)"]
        nginx["nginx<br/>dashboard/nginx.conf"]
        spa["React SPA 정적 파일<br/>dashboard/src (Vite 빌드)"]
    end

    subgraph serverBox["server 컨테이너 (Spring WebFlux :8080)"]
        subgraph securityBox["security"]
            adminChain["adminChain<br/>/admin/** · 관리자 토큰"]
            gatewayChain["gatewayChain<br/>/v1/** · 가상 키"]
            defaultChain["defaultChain<br/>/actuator/health만 허용"]
            authManager["TollgateAuthenticationManager"]
        end
        subgraph apiBox["컨트롤러"]
            adminApi["admin<br/>ProjectController · VirtualKeyController<br/>PriceController · ProviderController · UsageController"]
            relayApi["relay<br/>OpenAiChatController · AnthropicMessagesController<br/>ModelsController"]
        end
        subgraph serviceBox["서비스"]
            relayEngine["RelayEngine<br/>ProtocolAdapter: OpenAiAdapter · AnthropicAdapter"]
            registry["ProviderRegistry<br/>tollgate.providers 라우팅"]
            keyServices["ProjectService · VirtualKeyService"]
            pricing["PricingService · CostCalculator"]
            recorder["UsageRecorder"]
            usageQuery["UsageQueryService · UsageQueries"]
            seeder["PriceSeeder<br/>ApplicationRunner"]
        end
        subgraph dbBox["db"]
            repos["Spring Data R2DBC 저장소<br/>ProjectRepository · VirtualKeyRepository<br/>ModelPriceRepository · UsageRecordRepository"]
            flyway["Flyway<br/>db/migration/V1__init.sql"]
        end
    end

    postgres[("PostgreSQL 18<br/>projects · virtual_keys<br/>model_prices · usage_records")]
    pricesFile["config/prices.example.yaml<br/>TOLLGATE_PRICES_FILE"]

    subgraph upstreamBox["상위 공급처 (application.yml의 tollgate.providers)"]
        fake["fake-provider 컨테이너 :9090<br/>OpenAiFakeController · AnthropicFakeController"]
        ollama["ollama<br/>호스트 :11434"]
        openai["openai<br/>api.openai.com"]
        exaone["exaone<br/>api.exaone.ai"]
        anthropic["anthropic<br/>api.anthropic.com"]
    end

    browser -->|"HTTP :3000"| nginx
    nginx -->|"정적 파일 제공"| spa
    nginx -->|"프록시 /admin/"| adminChain
    nginx -->|"프록시 /v1/"| gatewayChain
    apiClient -->|"HTTP :8080 · /v1/* · JSON, SSE"| gatewayChain
    apiClient -.->|"/actuator/health · 인증 없음"| defaultChain

    adminChain --> authManager
    gatewayChain --> authManager
    authManager -->|"가상 키 인증"| keyServices
    adminChain -->|"ROLE_ADMIN"| adminApi
    gatewayChain -->|"ROLE_GATEWAY_KEY"| relayApi

    adminApi --> keyServices
    adminApi --> pricing
    adminApi --> registry
    adminApi --> usageQuery
    relayApi --> relayEngine
    relayApi -->|"GET /v1/models"| registry
    relayApi -->|"GET /v1/models"| pricing
    relayEngine --> registry
    relayEngine -->|"요청마다 1건 기록"| recorder
    recorder -->|"비용 계산"| pricing
    seeder -->|"기동 시 importFile"| pricing
    seeder -.->|"읽기"| pricesFile

    keyServices --> repos
    pricing --> repos
    recorder --> repos
    usageQuery -->|"DatabaseClient SQL"| postgres
    repos -->|"R2DBC"| postgres
    flyway -->|"JDBC · 기동 시 마이그레이션"| postgres

    relayEngine -->|"WebClient · HTTP POST · JSON, SSE"| upstreamBox
```

## 2. 모듈 의존성

소스의 import 문을 집계해 만든 의존성 그래프다. 화살표는 "출발 쪽이 도착 쪽을 import한다"는 뜻이다. server는 `tokentollgate.*` 패키지, fake-provider는 `fakeprovider.*` 패키지, dashboard는 `dashboard/src` 아래 디렉터리 단위이며 테스트 소스는 뺐다.

- **순환 의존성: 없음.** server와 fake-provider는 패키지 수준에서, dashboard는 파일 수준에서 순환이 없다.
- dashboard의 `src` 루트는 진입점(`main.tsx`, `App.tsx`)과 공용 함수(`format.ts`, `updates.ts`)로 나눠 그렸다. 루트를 한 덩어리로 보면 `App.tsx → pages → format.ts` 때문에 디렉터리 수준에서는 순환처럼 보이지만, 파일 사이에는 순환이 없다.
- `pages/*`는 `LoginPage.tsx`와 하위 디렉터리 넷(`keys`, `overview`, `prices`, `requests`)을 하나로 합친 것이다. 의존 대상이 거의 같아 합쳤다.
- server의 루트 패키지(`TokenTollgateApplication`)는 다른 패키지와 import 관계가 없어 그리지 않았다.
- 모듈 사이의 빌드 의존은 `server/build.gradle.kts`의 `testImplementation(project(":fake-provider"))` 하나다. dashboard는 서버와 HTTP로만 통신한다.
- 근거: `server/src/main/kotlin`, `fake-provider/src/main/kotlin`, `dashboard/src`의 import 문, `settings.gradle.kts`, `server/build.gradle.kts`.

SVG: [deps.svg](deps.svg)

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 600}}}%%
graph LR
    subgraph serverBox["server · tokentollgate.* (main)"]
        s_admin["admin"]
        s_relay_openai["relay.openai"]
        s_relay_anthropic["relay.anthropic"]
        s_relay["relay"]
        s_security["security"]
        s_usage["usage"]
        s_keys["keys"]
        s_pricing["pricing"]
        s_upstream["upstream"]
        s_gateway["gateway"]
        s_db["db"]
        s_common["common"]
    end

    s_admin --> s_keys
    s_admin --> s_pricing
    s_admin --> s_upstream
    s_admin --> s_usage
    s_admin --> s_db
    s_admin --> s_common
    s_relay_openai --> s_relay
    s_relay_openai --> s_security
    s_relay_openai --> s_pricing
    s_relay_openai --> s_upstream
    s_relay_openai --> s_db
    s_relay_anthropic --> s_relay
    s_relay_anthropic --> s_security
    s_relay_anthropic --> s_pricing
    s_relay_anthropic --> s_upstream
    s_relay_anthropic --> s_gateway
    s_relay_anthropic --> s_db
    s_relay --> s_usage
    s_relay --> s_security
    s_relay --> s_pricing
    s_relay --> s_upstream
    s_relay --> s_gateway
    s_relay --> s_db
    s_security --> s_keys
    s_security --> s_gateway
    s_usage --> s_security
    s_usage --> s_pricing
    s_usage --> s_db
    s_usage --> s_common
    s_keys --> s_db
    s_keys --> s_common
    s_pricing --> s_db
    s_pricing --> s_common

    subgraph fakeBox["fake-provider · fakeprovider.* (main)"]
        f_openai["openai"]
        f_anthropic["anthropic"]
        f_auth["auth"]
        f_error["error"]
        f_common["common"]
        f_directive["directive"]
        f_text["text"]
        f_root["(root)<br/>FakeProperties"]
    end

    f_openai --> f_common
    f_openai --> f_directive
    f_openai --> f_error
    f_openai --> f_text
    f_anthropic --> f_common
    f_anthropic --> f_directive
    f_anthropic --> f_error
    f_anthropic --> f_text
    f_auth --> f_common
    f_auth --> f_error
    f_auth --> f_root
    f_error --> f_common
    f_error --> f_directive
    f_common --> f_directive
    f_common --> f_root
    f_directive --> f_root

    subgraph dashBox["dashboard · src/* (테스트 제외)"]
        d_entry["main.tsx · App.tsx"]
        d_pages["pages/*<br/>LoginPage, keys, overview, prices, requests"]
        d_usage["usage"]
        d_layout["layout"]
        d_auth["auth"]
        d_router["router"]
        d_components["components"]
        d_api["api"]
        d_helpers["format.ts · updates.ts"]
    end

    d_entry --> d_pages
    d_entry --> d_layout
    d_entry --> d_auth
    d_entry --> d_router
    d_pages --> d_usage
    d_pages --> d_layout
    d_pages --> d_auth
    d_pages --> d_router
    d_pages --> d_components
    d_pages --> d_api
    d_pages --> d_helpers
    d_usage --> d_components
    d_usage --> d_api
    d_usage --> d_helpers
    d_layout --> d_auth
    d_layout --> d_router
    d_auth --> d_api

    subgraph moduleBox["빌드 단위"]
        m_server["server<br/>Gradle 모듈"]
        m_fake["fake-provider<br/>Gradle 모듈"]
        m_dash["dashboard<br/>npm 패키지 · 다른 모듈에 빌드 의존 없음"]
    end

    m_server -.->|"testImplementation · 테스트에서만"| m_fake
```

## 3. 중계 요청 흐름: `POST /v1/chat/completions`

게이트웨이의 본 경로다. 요청 본문 검사, 공급처 라우팅, 상위 호출, 응답 중계, 실패 종류별 응답과 사용 기록까지를 보여 준다. `POST /v1/messages`도 어댑터(`AnthropicAdapter`)만 다르고 같은 `RelayEngine.relay`를 탄다.

- 어떤 경로로 끝나든 `Attempt.finish`가 요청당 한 번 사용 기록을 남긴다. 예외는 본문 검사에서 걸린 400으로, 이때는 기록하지 않는다.
- 근거: `relay/RelayEngine.kt`, `relay/RelayErrors.kt`, `relay/RelayJson.kt`, `relay/HeaderRules.kt`, `relay/openai/OpenAiChatController.kt`, `relay/anthropic/AnthropicMessagesController.kt`, `upstream/ProviderRegistry.kt` (모두 `server/src/main/kotlin/tokentollgate/` 아래).

SVG: [flow-post-v1-chat-completions.svg](flow-post-v1-chat-completions.svg)

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 600}}}%%
flowchart TD
    start(["POST /v1/chat/completions<br/>POST /v1/messages도 어댑터만 다르고 같은 흐름"])
    auth[["gatewayChain 가상 키 인증<br/>flow-VirtualKeyService-authenticate 참고"]]
    ctrl["OpenAiChatController.chatCompletions<br/>AnthropicMessagesController.messages"]
    relay["RelayEngine.relay<br/>requestId 생성 · x-tollgate-request-id 응답 헤더"]
    inspect{"adapter.inspect<br/>본문이 JSON 객체이고<br/>model이 있는가"}
    bad(["400 invalid_request_error<br/>RelayErrors.badRequest · 사용 기록 없음"])
    route{"ProviderRegistry.route<br/>패턴 일치 → 기본 공급처 → 없음"}
    notFound["Attempt.refuse<br/>404 model_not_found · outcome=gateway_error"]
    disabled["Attempt.refuse<br/>503 provider_disabled · outcome=gateway_error"]
    isStream{"inspected.stream"}
    stream[["streamUpstream<br/>flow-RelayEngine-streamUpstream 참고"]]
    request["upstreamRequest<br/>같은 경로로 POST · HeaderRules로 헤더 복사<br/>공급처 키를 Authorization: Bearer로 설정"]
    upstreamCall[["공급처 호출<br/>ProviderRegistry.client · WebClient"]]
    threw{"호출 중 예외"}
    plain["relayPlain<br/>본문 전체를 읽어 상태·헤더·바이트 그대로 응답"]
    is2xx{"상위 응답이 2xx"}
    okOutcome["Attempt.plainResponse<br/>outcome=ok · usage를 읽으면 usageSource=response"]
    errOutcome["Attempt.plainResponse<br/>outcome=upstream_error<br/>errorType=본문의 error.type 또는 http_상태코드"]
    cancelled["CancellationException<br/>Attempt.abortedByClient · outcome=client_abort"]
    committed{"응답이 이미 시작됐는가<br/>response.isCommitted"}
    noBody["오류 본문 없이 기록만<br/>cancelled이거나 상태 미정이면 client_abort"]
    kind{"Attempt.failed<br/>예외 종류"}
    e504["504 upstream_timeout<br/>outcome=timeout"]
    e502["502 upstream_unreachable<br/>outcome=upstream_error"]
    e500["500 gateway_error<br/>outcome=gateway_error"]
    finish["Attempt.finish · 요청당 한 번<br/>UsageRecorder.record"]
    record[("usage_records<br/>flow-UsageRecorder-save 참고")]
    done(["응답 종료"])

    start --> auth --> ctrl --> relay --> inspect
    inspect -->|"아니오 · InvalidRelayRequestException"| bad
    inspect -->|"예"| route
    route -->|"Route.NotFound"| notFound
    route -->|"Route.Disabled · 키 환경 변수가 비어 있음"| disabled
    route -->|"Route.To"| isStream
    isStream -->|"true"| stream
    isStream -->|"false · callUpstream"| request
    request --> upstreamCall --> threw
    threw -->|"없음"| plain
    plain --> is2xx
    is2xx -->|"예"| okOutcome
    is2xx -->|"아니오"| errOutcome
    threw -->|"CancellationException · 클라이언트 이탈"| cancelled
    threw -->|"그 밖의 Exception"| committed
    committed -->|"예"| noBody
    committed -->|"아니오"| kind
    kind -->|"ReadTimeoutException · TimeoutException"| e504
    kind -->|"WebClientRequestException"| e502
    kind -->|"그 밖"| e500
    stream --> finish
    okOutcome --> finish
    errOutcome --> finish
    notFound --> finish
    disabled --> finish
    cancelled --> finish
    noBody --> finish
    e504 --> finish
    e502 --> finish
    e500 --> finish
    finish --> record --> done
```

## 4. 스트리밍 중계: `RelayEngine.streamUpstream`

`stream: true` 요청에서 상위 SSE 스트림을 프레임 단위로 나눠 그대로 전달하면서, 관찰자(`StreamObserver`)가 모델·사용량·첫 토큰 시각을 읽는 과정이다. 스트림이 끝난 뒤 결과(`outcome`)를 어떻게 판정하는지도 담았다.

- 프레임은 바이트를 바꾸지 않고 전달한다. 버리는 것은 클라이언트가 요청하지 않은 OpenAI usage 청크 하나뿐이다.
- 클라이언트가 연결을 끊은 경우 그림에서는 결과 판정의 첫 가지로 그렸다. 실제로는 `RelayEngine.relay`의 `CancellationException` 처리로 먼저 끝날 수도 있고, 어느 쪽이든 `client_abort`로 기록된다. 어느 경로를 타는지는 실행 시점에 따라 달라 코드만으로 확정하지 못했다(추정).
- 근거: `relay/RelayEngine.kt`, `relay/SseFrames.kt`, `relay/ProtocolAdapter.kt`, `relay/openai/OpenAiRequestInspector.kt`, `relay/openai/OpenAiStreamObserver.kt`, `relay/anthropic/AnthropicStreamObserver.kt`.

SVG: [flow-RelayEngine-streamUpstream.svg](flow-RelayEngine-streamUpstream.svg)

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 600}}}%%
flowchart TD
    start(["RelayEngine.streamUpstream<br/>요청 본문의 stream이 true"])
    note["OpenAI 요청에 stream_options.include_usage가 없으면<br/>OpenAiRequestInspector가 상위로 보내는 본문에만 넣는다"]
    request["upstreamRequest<br/>공급처에 POST"]
    isSse{"상위 응답이 2xx이고<br/>Content-Type이 text/event-stream"}
    plain["relayPlain<br/>일반 응답으로 그대로 중계 · Attempt.plainResponse"]
    head["상태·헤더 복사 · HeaderRules.copyResponseHeaders<br/>SseFrameSplitter와 adapter.streamObserver 준비"]
    read["상위 바이트 수신<br/>제한 시간: provider.settings.streamIdleTimeout"]
    failed{"수신 중 오류"}
    split["SseFrameSplitter.feed<br/>빈 줄 기준으로 SseFrame 분리 · 바이트 보존"]
    observe{"StreamObserver.observe<br/>전달할 프레임인가"}
    drop["버림<br/>OpenAI usage 청크 · 클라이언트가 요청하지 않은 경우"]
    forward["frame.raw를 그대로 클라이언트에 쓰고 flush"]
    more{"상위 스트림이 끝났는가"}
    tail["SseFrameSplitter.finish<br/>빈 줄 없이 남은 바이트도 같은 방식으로 관찰·전달"]
    isTimeout{"isTimeout"}
    fTimeout["StreamFailure<br/>outcome=timeout · upstream_timeout"]
    fInterrupted["StreamFailure<br/>outcome=upstream_error · stream_interrupted"]
    errorFrame["adapter.errorFrame<br/>오류 프레임을 마지막으로 전송"]
    measure["Attempt.streamResponse<br/>modelServed = observer.model<br/>ttftMs = observer.firstContentNanos 기준"]
    result{"결과 판정<br/>위에서부터 처음 맞는 조건"}
    rAbort["outcome=client_abort"]
    rFailure["StreamFailure의 outcome과 errorType"]
    rError["outcome=upstream_error<br/>상위 오류 프레임의 type 또는 stream_error"]
    rOk["outcome=ok<br/>observer.usage가 있으면 usageSource=stream"]
    rCut["outcome=upstream_error<br/>stream_interrupted"]
    finish["Attempt.finish<br/>UsageRecorder.record"]
    done(["스트림 종료"])

    start --> note --> request --> isSse
    isSse -->|"아니오"| plain --> finish
    isSse -->|"예"| head --> read --> failed
    failed -->|"없음"| split --> observe
    observe -->|"false"| drop --> more
    observe -->|"true"| forward --> more
    more -->|"아니오 · 다음 버퍼"| read
    more -->|"예"| tail --> measure
    failed -->|"예외"| isTimeout
    isTimeout -->|"예"| fTimeout --> errorFrame
    isTimeout -->|"아니오"| fInterrupted --> errorFrame
    errorFrame --> measure
    measure --> result
    result -->|"cancelled · 클라이언트가 연결을 끊음"| rAbort
    result -->|"streamFailure 있음"| rFailure
    result -->|"observer.sawError"| rError
    result -->|"observer.done · [DONE] 또는 message_stop"| rOk
    result -->|"그 밖 · 종료 표식 없이 끝남"| rCut
    rAbort --> finish
    rFailure --> finish
    rError --> finish
    rOk --> finish
    rCut --> finish
    finish --> done
```

## 5. 가상 키 인증: `VirtualKeyService.authenticate`

`/v1/**` 요청이 컨트롤러에 닿기 전에 거치는 인증이다. 헤더에서 키를 읽고, SHA-256 해시로 조회하고, 폐기된 키와 보관된 프로젝트를 거부한다. 거부 응답은 요청 경로에 따라 OpenAI 형식이나 Anthropic 형식으로 나간다.

- 키는 평문으로 저장하지 않으므로 조회는 해시 비교 한 번이다. 오류 메시지에는 키 값이 들어가지 않는다.
- `last_used_at` 갱신은 요청을 막지 않는 별도 쓰기이고, 키마다 60초에 한 번만 한다.
- 근거: `security/SecurityConfig.kt`, `security/Converters.kt`, `security/AuthenticationManagers.kt`, `security/EntryPoints.kt`, `keys/VirtualKeyService.kt`, `keys/KeyGenerator.kt`, `gateway/ProtocolErrorWriter.kt`, `db/Repositories.kt`.

SVG: [flow-VirtualKeyService-authenticate.svg](flow-VirtualKeyService-authenticate.svg)

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 600}}}%%
flowchart TD
    start(["/v1/** 요청<br/>SecurityConfig.gatewayChain"])
    convert{"VirtualKeyConverter.convert<br/>Authorization: Bearer 또는 x-api-key에<br/>키가 있는가"}
    missing["AuthenticationCredentialsNotFoundException<br/>401 Missing API key"]
    tag{"X-Tollgate-Tag가 100자 이하인가"}
    badTag["InvalidRequestException<br/>400 invalid_request_error"]
    manager["TollgateAuthenticationManager.authenticateKey<br/>VirtualKeyCredentials"]
    hash["VirtualKeyService.authenticate<br/>KeyGenerator.hash · SHA-256"]
    lookup[("VirtualKeyRepository.findAuthViewByKeyHash<br/>virtual_keys JOIN projects")]
    found{"해시가 같은 행이 있는가"}
    unknown["KeyRejection.Unknown<br/>BadCredentialsException · 401 Invalid API key"]
    revoked{"revoked_at이 있는가"}
    rRevoked["KeyRejection.Revoked<br/>BadCredentialsException · 401 API key has been revoked"]
    archived{"프로젝트의 archived_at이 있는가"}
    rArchived["KeyRejection.ProjectArchived<br/>DisabledException · 401 Project is archived"]
    accepted["KeyAuthResult.Accepted"]
    use["VirtualKeyService.recordUse<br/>키마다 60초에 한 번 · 요청을 막지 않음"]
    update[("VirtualKeyRepository.updateLastUsedAt<br/>virtual_keys.last_used_at · 실패하면 로그만")]
    principal["VirtualKeyAuthentication · ROLE_GATEWAY_KEY<br/>AuthenticatedKey: keyId, projectId, keyPrefix, tag"]
    controller(["컨트롤러로 진행<br/>AuthenticatedKey를 주입받음"])
    entry["GatewayEntryPoint.commence<br/>오류 메시지에 키 값은 넣지 않음"]
    shape{"ProtocolErrorWriter.write<br/>경로가 /v1/messages로 시작하는가"}
    anthropicShape(["Anthropic 오류 형식으로 응답<br/>401이면 authentication_error"])
    openAiShape(["OpenAI 오류 형식으로 응답<br/>401이면 code=invalid_api_key"])

    start --> convert
    convert -->|"아니오"| missing
    convert -->|"예"| tag
    tag -->|"아니오"| badTag
    tag -->|"예"| manager --> hash --> lookup --> found
    found -->|"아니오"| unknown
    found -->|"예"| revoked
    revoked -->|"예"| rRevoked
    revoked -->|"아니오"| archived
    archived -->|"예"| rArchived
    archived -->|"아니오"| accepted
    accepted --> use
    use -.->|"비동기"| update
    use --> principal --> controller
    missing --> entry
    badTag --> entry
    unknown --> entry
    rRevoked --> entry
    rArchived --> entry
    entry --> shape
    shape -->|"예"| anthropicShape
    shape -->|"아니오"| openAiShape
```

## 6. 사용 기록과 비용 계산: `UsageRecorder.save`

요청이 끝난 뒤 `usage_records`에 한 행을 쓰는 과정이다. 요청 시각에 유효한 단가를 찾아 비용을 계산하고, 단가가 없거나 사용량을 읽지 못한 경우에는 비용 없이 기록한다.

- 저장은 응답 경로 밖의 코루틴에서 실행된다. 실패해도 로그만 남고 클라이언트 응답은 바뀌지 않는다.
- 비용 식은 `CostCalculator`의 주석과 구현을 그대로 옮겼다. 단가는 토큰 백만 개당 금액이다.
- 근거: `usage/UsageRecorder.kt`, `pricing/PricingService.kt`, `pricing/CostCalculator.kt`, `db/Repositories.kt`, `db/Entities.kt`.

SVG: [flow-UsageRecorder-save.svg](flow-UsageRecorder-save.svg)

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 600}}}%%
flowchart TD
    start(["Attempt.finish<br/>UsageRecorder.record"])
    launch["scope.launch · usage-recorder 코루틴<br/>응답 경로 밖에서 실행"]
    save["UsageRecorder.save"]
    model["priceModel = modelServed<br/>없으면 modelRequested"]
    hasUsage{"draft.usage가 있는가"}
    noUsage["토큰 0으로 기록<br/>cost와 price_id는 null"]
    cost["PricingService.cost<br/>provider, priceModel, startedAt, usage"]
    lookup[("ModelPriceRepository.findEffective<br/>model_prices에서 effective_from이<br/>startedAt 이하인 최신 행")]
    priced{"단가 행이 있는가"}
    unpriced["CostResult.Unpriced<br/>cost와 price_id는 null"]
    calc["CostCalculator.cost<br/>(input − cached) × input 단가<br/>+ cached × (cached 단가, 없으면 input 단가)<br/>+ output × output 단가<br/>÷ 1,000,000 · 소수 8자리 HALF_UP"]
    pricedResult["CostResult.Priced<br/>cost, priceId"]
    build["UsageRecord 조립<br/>키·프로젝트·태그, 모델, 토큰, 결과, 소요 시간"]
    insert[("UsageRecordRepository.save<br/>usage_records INSERT")]
    failed{"save 중 예외"}
    warn(["log.warn만 남김<br/>클라이언트 응답에는 영향 없음"])
    info(["log.info · relay request_id=...<br/>키는 keyPrefix만 기록"])

    start --> launch --> save --> model --> hasUsage
    hasUsage -->|"아니오"| noUsage --> build
    hasUsage -->|"예"| cost --> lookup --> priced
    priced -->|"아니오"| unpriced --> build
    priced -->|"예"| calc --> pricedResult --> build
    build --> insert --> failed
    failed -->|"예"| warn
    failed -->|"아니오"| info
```

## 7. 사용량 요약 조회: `GET /admin/v1/usage/summary`

대시보드의 개요 화면이 부르는 조회다. 관리자 토큰 확인, 기간·그룹·필터 검증, 집계 SQL, 정렬과 합계까지를 보여 준다.

- 호출자가 준 값은 시간대 이름까지 모두 bind 파라미터로 전달된다. SQL 문자열은 고정된 조각으로만 조립된다.
- 일별 묶음은 `tz`로 받은 시간대 기준이고, 저장된 시각은 UTC다.
- 근거: `admin/UsageController.kt`, `admin/AdminErrorHandler.kt`, `usage/UsageQuery.kt`, `usage/UsageQueryService.kt`, `usage/UsageQueries.kt`, `security/SecurityConfig.kt`, `security/AuthenticationManagers.kt`.

SVG: [flow-get-admin-v1-usage-summary.svg](flow-get-admin-v1-usage-summary.svg)

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 600}}}%%
flowchart TD
    start(["GET /admin/v1/usage/summary<br/>from, to, groupBy, tz, projectId, tag, model, provider"])
    token{"SecurityConfig.adminChain<br/>Bearer 토큰이 tollgate.admin.token과 같은가<br/>MessageDigest.isEqual"}
    unauthorized(["401 unauthorized<br/>AdminEntryPoint"])
    controller["UsageController.summary"]
    window{"UsageWindow.of<br/>tz가 유효 · from이 to보다 앞 · 범위 366일 이하<br/>기본값: to=지금, from=to−7일"}
    group{"GroupBy.parse<br/>day, project, model, tag 중 하나 · 기본 day"}
    filter{"UsageFilter.of<br/>projectId가 UUID 형식인가"}
    bad(["400 bad_request<br/>BadRequestException → AdminErrorHandler"])
    service["UsageQueryService.summary"]
    sql["UsageQueries.summary<br/>groupBy에 따라 key 열을 고름<br/>호출자가 준 값은 모두 bind 파라미터"]
    db[("usage_records 집계 SELECT<br/>groupBy=project일 때만 projects LEFT JOIN<br/>요청 수 · 성공 수 · 토큰 합 · 비용 합<br/>price_missing · usage_unknown")]
    order{"groupBy가 day인가"}
    byDay["날짜 오름차순"]
    byCost["비용 내림차순 → 요청 수 내림차순 → key"]
    totals["totals = 버킷 합계"]
    response(["200 UsageSummaryResponse"])

    start --> token
    token -->|"아니오 · 토큰 없음 포함"| unauthorized
    token -->|"예 · ROLE_ADMIN"| controller --> window
    window -->|"아니오"| bad
    window -->|"예"| group
    group -->|"그 밖의 값"| bad
    group -->|"유효"| filter
    filter -->|"아니오"| bad
    filter -->|"예"| service --> sql --> db --> order
    order -->|"예"| byDay --> totals
    order -->|"아니오"| byCost --> totals
    totals --> response
```

## 8. DB 엔티티 관계

테이블 넷과 그 사이의 외래 키다. 프로젝트가 가상 키를 여러 개 갖고, 사용 기록은 키·프로젝트·(있으면) 적용된 단가 행을 가리킨다.

- **이 그림은 코드 기준이다.** 마이그레이션(`V1__init.sql`)과 엔티티(`Entities.kt`), 저장소(`Repositories.kt`)를 읽어 그렸고, 실제 DB에 접속해 확인하지 않았다. 운영 중인 DB의 스키마는 이와 다를 수 있다.
- Flyway가 만드는 이력 테이블(`flyway_schema_history`)과 인덱스 셋(`virtual_keys_project_id_idx`, `usage_records_project_started_idx`, `usage_records_started_idx`)은 그림에 넣지 않았다.
- `model_prices`의 유니크 제약은 `(provider, model, effective_from)` 복합이다. `protocol`, `usage_source`, `outcome`은 CHECK 제약으로 값이 고정된 text 열이고, 코드에서는 `db/Enums.kt`의 enum과 대응한다.
- 근거: `server/src/main/resources/db/migration/V1__init.sql`, `server/src/main/kotlin/tokentollgate/db/Entities.kt`, `db/Repositories.kt`, `db/Enums.kt`.

SVG: [er.svg](er.svg)

```mermaid
erDiagram
    projects ||--o{ virtual_keys : "project_id"
    projects ||--o{ usage_records : "project_id"
    virtual_keys ||--o{ usage_records : "key_id"
    model_prices |o--o{ usage_records : "price_id, null 허용"

    projects {
        uuid id PK
        text name UK
        timestamptz created_at
        timestamptz archived_at "null 허용"
    }

    virtual_keys {
        uuid id PK
        uuid project_id FK
        text name
        text key_prefix
        text key_hash UK "SHA-256 해시만 저장"
        timestamptz created_at
        timestamptz revoked_at "null 허용"
        timestamptz last_used_at "null 허용"
    }

    model_prices {
        uuid id PK
        text provider "provider, model, effective_from 복합 유니크"
        text model
        numeric input_per_mtok "numeric 18,8"
        numeric output_per_mtok "numeric 18,8"
        numeric cached_input_per_mtok "numeric 18,8, null 허용"
        text currency "기본값 USD"
        timestamptz effective_from
        timestamptz created_at
    }

    usage_records {
        bigserial id PK
        uuid request_id UK
        timestamptz started_at
        uuid key_id FK
        uuid project_id FK
        text tag "null 허용"
        text protocol "openai, anthropic"
        text provider
        text model_requested
        text model_served "null 허용"
        integer input_tokens
        integer output_tokens
        integer cached_input_tokens
        numeric cost "numeric 18,8, null 허용"
        uuid price_id FK "null 허용"
        text usage_source "response, stream, none"
        integer ttft_ms "null 허용"
        integer duration_ms
        integer http_status
        boolean streamed
        text outcome "ok, upstream_error, client_abort, timeout, gateway_error"
        text error_type "null 허용"
    }
```

## 검증

다이어그램에 쓴 이름이 실제 코드에 있는지 기계적으로 대조했다.

- 방법: Mermaid 원본의 라벨에서 코드처럼 보이는 토큰(클래스, 메서드, 파일 경로, 테이블·열, URL 경로, 오류 코드)을 뽑아, 저장소의 추적 파일 중 `docs/` 밖에서 찾았다.
- 결과: 토큰 360개 중 199개는 선언(클래스·함수·속성, 스키마의 테이블·열, 존재하는 파일)으로 확인했고, 146개는 선언은 아니지만 코드나 설정에 쓰이는 이름이다(`WebClient`, `DatabaseClient`, `BadCredentialsException` 같은 프레임워크 타입, 설정 키, 문자열 리터럴, 일반 단어).
- 나머지 15개는 코드에 그 표기 그대로는 없다. 모두 코드 요소가 아닌 표기였다: ER 표기(`PK`, `FK`, `UK`)와 `2xx`, 설정에는 소문자로만 나오는 기술 이름(`Flyway`, `R2DBC`, `JDBC`, `Spring Data`), 설명용 단어(`SDK`, `SPA`), 한글 조사가 붙어 잘린 경로 셋(경로 자체는 코드에 있다), 디렉터리 묶음 표기 `pages/*`, MIME 문자열 `text/event-stream`(코드에서는 `MediaType.TEXT_EVENT_STREAM`).
- 존재하지 않는 클래스, 파일, 테이블을 가리키는 이름은 없었다.

이 대조는 이름의 존재만 확인한다. 화살표(관계)는 의존성 그림은 import 집계로, 나머지는 해당 코드를 읽어 그렸다.

### 추정으로 표시한 것

| 다이어그램 | 요소 | 이유 |
|---|---|---|
| 전체 시스템 구조 | "API 클라이언트", "관리자 브라우저" | 코드에 없는 외부 사용자다. `examples/requests.http`와 대시보드의 존재로 미루어 그렸다. |
| 전체 시스템 구조 | API 클라이언트 → `defaultChain` 화살표 | `/actuator/health`의 호출자는 서버 코드에 없다. 저장소에서 확인한 호출자는 Dockerfile의 HEALTHCHECK와 `scripts/smoke.sh`다. |
| 스트리밍 중계 | 클라이언트 연결 끊김이 결과 판정으로 가는 경로 | `client_abort`로 기록되는 것은 코드로 확인했지만, 두 처리 경로 중 어느 쪽을 타는지는 실행 시점에 달려 있다. |

## 한계

- 흐름도는 주요 분기만 담았다. 로그, 헤더 복사 규칙의 세부, 타임아웃 값은 코드와 설정을 봐야 한다.
- 의존성 그림은 import 문 기준이다. 같은 패키지 안의 참조와 Spring의 컴포넌트 스캔처럼 import 없이 이어지는 관계는 나타나지 않는다.
- SVG는 글꼴을 내장해 파일당 250~410KB이고 배경이 투명하다. 어두운 테마에서는 선과 바깥 글자가 잘 보이지 않을 수 있으니 그럴 때는 위의 Mermaid 블록을 본다.
