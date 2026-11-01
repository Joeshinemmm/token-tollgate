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

## 빌드와 테스트

요구 사항: JDK 25, Node.js 26, Docker(통합 테스트용).

```bash
./gradlew build
```

```bash
cd dashboard && npm install && npm run build
```

## 라이선스

[MIT](LICENSE)
