# 011 배포 구성: Dockerfile, compose, nginx, 스모크, CI

- 명세 작성·승인·구현·검증: 2026-11-08
- 선행: 001~010 전부. 서버 jar(`bootJar`), 가짜 공급처 jar, 대시보드 빌드 산출물(`dashboard/dist`)이 각각 돈다
- 후속: 012가 이 compose 위에서 로컬 Ollama를 연결해 완료 기준 1~5를 대조한다. 4단계 k3s 배포가 여기서 만든 이미지를 그대로 쓴다

## 목표

`docker compose up` 한 번으로 PostgreSQL, 서버, 가짜 공급처, 대시보드가 뜨고(브리프 완료 기준 1), 스모크 스크립트가 키 발급부터 네 가지 중계 호출과 기록 확인까지 curl로 지나간다(완료 기준 2). 같은 검사를 GitHub Actions에서 PR마다 돌려 저장소가 늘 빌드·테스트·기동되는 상태임을 보인다. README에 "한 번에 띄우기"와 도구 연결 방법을 적는다.

## 완료 기준

1. 저장소를 받은 PC에서 `.env.example`을 `.env`로 복사하고 `docker compose up -d --build`만 하면 네 서비스가 모두 `healthy`가 된다. 실제 공급처 키 없이 가짜 공급처로 전체가 동작한다.
2. `scripts/smoke.sh`가 프로젝트·키 생성 → OpenAI·Anthropic 형식 × 일반·스트리밍 네 번 호출 → 사용량 기록 확인 → 대시보드 정적 파일과 `/admin` 프록시 확인까지 통과하고 0으로 끝난다. 실패하면 어느 단계인지 찍고 0이 아닌 값으로 끝난다.
3. 대시보드 컨테이너(3000)가 `/admin/`과 `/v1/`을 서버로 프록시하고, `/v1/`의 SSE 스트림이 끊기거나 버퍼링되지 않는다.
4. `.github/workflows/ci.yml`이 PR과 `main` push에서 서버 빌드·테스트(Testcontainers 포함), 대시보드 lint·test·build, compose 기동 + 스모크를 돌린다. 이 작업의 PR이 첫 CI 실행이고 녹색이어야 한다.
5. README의 "로컬 실행"이 compose 한 번 기동을 첫째 방법으로, `bootRun` + `npm run dev`를 개발 방법으로 나눠 적고, 도구 연결 방법(base URL, 키)을 담는다.

## 명세

### 1. 파일

| 파일 | 역할 |
|---|---|
| `server/Dockerfile` | 멀티 스테이지. 빌드: `eclipse-temurin:25-jdk-alpine`에서 Gradle 래퍼로 `:server:bootJar`. 실행: `eclipse-temurin:25-jre-alpine`, 비루트 사용자, Boot 계층 추출(`java -Djarmode=tools -jar app.jar extract --layers --launcher`), `JarLauncher`로 기동 |
| `fake-provider/Dockerfile` | 같은 구조로 `:fake-provider:bootJar` |
| `dashboard/Dockerfile` | 빌드: `node:26-alpine`에서 `npm ci` + `npm run build`(`tsc -b` 포함). 실행: `nginx:1.30.6-alpine`에 `dist/`와 `dashboard/nginx.conf` |
| `dashboard/nginx.conf` | 정적 파일 + SPA 폴백 + `/admin/`·`/v1/` 프록시(3절) |
| `.dockerignore` | `.git`, `node_modules`, `dashboard/dist`, `build/`, `.gradle/`, `docs/`, `.env` 등 제외. 빌드 컨텍스트가 저장소 루트이므로 필요하다 |
| `docker-compose.yml` | 네 서비스(2절). 기존 `postgres` 정의 유지 |
| `.env.example` | `DASHBOARD_PORT=3000` 추가, compose에서 쓰는 값과 `bootRun`에서 쓰는 값의 구분을 주석으로 |
| `scripts/smoke.sh` | 4절 |
| `.github/workflows/ci.yml` | 5절 |
| `README.md` | 로컬 실행 절 재구성, 도구 연결 방법, 스모크·CI 안내 |

### 2. compose

| 서비스 | 빌드/이미지 | 포트 | 환경 변수(컨테이너용 값은 compose에 고정) | 의존 |
|---|---|---|---|---|
| `postgres` | `postgres:18` (002 그대로) | `${POSTGRES_PORT:-5432}:5432` | `POSTGRES_DB/USER/PASSWORD` | 헬스체크 `pg_isready` (기존) |
| `server` | `context: .`, `dockerfile: server/Dockerfile` | `${SERVER_PORT:-8080}:8080` | `TOLLGATE_DB_HOST=postgres`, `TOLLGATE_DB_PORT=5432`, `POSTGRES_*`, `TOLLGATE_ADMIN_TOKEN`, `TOLLGATE_PRICES_FILE=/app/config/prices.example.yaml`(이미지에 `config/` 복사), `FAKE_PROVIDER_URL=http://fake-provider:9090`, `FAKE_API_KEY`, `OLLAMA_BASE_URL=http://host.docker.internal:11434` + `extra_hosts: host.docker.internal:host-gateway`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY` | `postgres` healthy, `fake-provider` started. 헬스체크 `wget -qO- http://localhost:8080/actuator/health` |
| `fake-provider` | `context: .`, `dockerfile: fake-provider/Dockerfile` | `${FAKE_PROVIDER_PORT:-9090}:9090` | `FAKE_*` 전부 | 헬스체크 `/actuator/health` |
| `dashboard` | `context: .`, `dockerfile: dashboard/Dockerfile` | `${DASHBOARD_PORT:-3000}:80` | 없음 | `server` started. 헬스체크 `wget -qO- http://localhost/` |

- 계획 4.7의 "PostgreSQL 호스트 노출 안 함"은 지키지 않고 기존처럼 `POSTGRES_PORT`로 노출한다. `bootRun` + `npm run dev` 개발 흐름이 compose의 DB를 쓰기 때문이다. 노출을 끄고 싶으면 `.env`에서 `POSTGRES_PORT`를 비우면 되는 대신, 그 경우 안내를 README에 적는다.
- `.env`는 호스트 기준 값(포트, 토큰, 키, DB 계정)만 담고, 컨테이너 사이 주소(`postgres`, `fake-provider`, `host.docker.internal`)와 이미지 안 경로는 compose 파일에 고정한다. 같은 `.env`가 `bootRun` 안내에도 쓰이므로 두 용도가 섞이지 않게 한다.
- `--wait`를 쓰면 compose가 헬스체크를 기다리므로 스모크 스크립트가 직접 폴링할 필요가 줄지만, 오래된 compose와 Windows Git Bash를 고려해 스크립트도 한 번 더 기다린다.
- 재시작 정책은 두지 않는다(로컬 개발 도구). 볼륨은 `pgdata` 하나.

### 3. nginx (`dashboard/nginx.conf`)

- `listen 80`, `root /usr/share/nginx/html`, `location / { try_files $uri /index.html; }`. 해시 라우터라 깊은 경로가 없지만 폴백은 두는 편이 안전하다.
- `/assets/`는 파일명에 해시가 있으므로 `Cache-Control: public, max-age=31536000, immutable`. `index.html`은 `no-cache`.
- `location /admin/`과 `location /v1/`: `proxy_pass http://server:8080;`, `proxy_http_version 1.1`, `proxy_set_header Connection ""`, `Host`·`X-Forwarded-For`·`X-Forwarded-Proto` 전달. `/v1/`에는 `proxy_buffering off`, `proxy_cache off`, `proxy_read_timeout 600s`(서버의 상류 응답 타임아웃보다 길게), `client_max_body_size 16m`(서버 코덱 한도와 맞춤). SSE가 `X-Accel-Buffering` 없이도 바로 흐르게 하는 설정이다.
- `/actuator/`는 프록시하지 않는다(헬스는 서버 포트에서 직접). 서버의 `x-tollgate-request-id` 응답 헤더는 그대로 통과한다.
- 도구는 `http://localhost:8080/v1`(직접)와 `http://localhost:3000/v1`(nginx 경유) 어느 쪽이든 쓸 수 있다. README에는 직접 연결을 기본으로 적고 nginx 경유는 "한 포트만 열 때"로 적는다.

### 4. 스모크 (`scripts/smoke.sh`)

bash 스크립트(Linux CI, Windows는 Git Bash). `curl`과 `python`(JSON 추출)만 쓴다. 저장소 루트에서 실행하고, `.env`가 있으면 읽고 없으면 `.env.example`의 기본값을 쓴다.

1. `docker compose up -d --build --wait`(실패하면 `docker compose logs --tail 50`을 찍고 종료).
2. 서버 `/actuator/health`가 `"status":"UP"`, 가짜 공급처 `/actuator/health` UP, 대시보드 `GET /`가 200이고 본문에 `<div id="root">`, 대시보드 경유 `GET /admin/v1/providers`(관리자 토큰)가 200 — 최대 60초 폴링.
3. 프로젝트 `smoke-<시각>` 생성, 키 발급(평문은 변수에만, 출력 안 함).
4. 네 번 호출: OpenAI 일반·스트리밍(`fake-small`, 스트리밍은 `stream_options.include_usage` 없이), Anthropic 일반·스트리밍(`fake-small`, `anthropic-version` 헤더 없이 기본값 확인). 각 응답의 `x-tollgate-request-id`를 모은다. 스트리밍은 `[DONE]`/`message_stop`이 오는지 본다. 한 번은 nginx 경유(`localhost:3000/v1/chat/completions`, 스트리밍)로 보내 프록시를 확인한다.
5. `GET /admin/v1/usage/records?limit=10`에 다섯 요청 ID가 모두 있고 `outcome=ok`, `cost`가 null이 아닌지(fake-small 단가는 `prices.example.yaml`에 있음) 확인. `GET /admin/v1/usage/summary?groupBy=model`에 `fake/fake-small`이 있는지 확인.
6. 결과를 단계별 `PASS`/`FAIL`로 찍고, 하나라도 실패면 1로 끝난다. 기본은 컨테이너와 데이터를 남긴다. `SMOKE_DOWN=1`이면 끝에 `docker compose down -v`.

### 5. CI (`.github/workflows/ci.yml`)

`on: pull_request`, `push: branches: [main]`. 동시 실행은 같은 브랜치끼리 취소(`concurrency`). 세 잡은 서로 독립이다.

| 잡 | 러너 | 단계 |
|---|---|---|
| `server` | `ubuntu-latest` | `actions/checkout@v7.0.1` → `actions/setup-java@v6.2.0`(temurin 25) → `gradle/actions/setup-gradle@v6.4.1`(캐시) → `./gradlew build`(단위·통합 테스트 포함. 러너에 Docker가 있어 Testcontainers가 돈다) → 테스트 결과를 artifact로 업로드(실패 때 보기) |
| `dashboard` | `ubuntu-latest` | checkout → `actions/setup-node@v7.1.0`(node 26, `cache: npm`, `cache-dependency-path: dashboard/package-lock.json`) → `npm ci` → `npm run lint` → `npm test` → `npm run build` |
| `smoke` | `ubuntu-latest` | checkout → `cp .env.example .env` → `scripts/smoke.sh`(compose가 이미지 셋을 빌드. Gradle 캐시가 없어 5분쯤 걸릴 것으로 예상, 실측해 기록) → 실패 시 `docker compose logs` 출력 |

- 액션 버전은 정확한 태그로 고정한다(2026-11-08 `gh api .../releases/latest`로 확인: checkout v7.0.1, setup-java v6.2.0, setup-node v7.1.0, gradle/actions v6.4.1). 커밋 SHA 고정은 하지 않는다(갱신 부담 대비 이점이 작은 공개 포트폴리오).
- 이미지 푸시, 릴리스, 배포는 하지 않는다(4단계).

### 6. 이미지와 도구 버전 (2026-11-08 Docker Hub·GitHub에서 확인)

| 용도 | 태그 | 확인 내용 |
|---|---|---|
| JVM 빌드 | `eclipse-temurin:25-jdk-alpine` | 2026-11-05 갱신. Temurin 25는 프로젝트의 JDK 25 toolchain과 같다 |
| JVM 실행 | `eclipse-temurin:25-jre-alpine` | 2026-11-05 갱신. JRE만 담아 작다. busybox `wget`으로 헬스체크 |
| 대시보드 빌드 | `node:26-alpine` | 2026-11-05 갱신. 로컬 Node 26.13.0과 같은 메이저 |
| 대시보드 실행 | `nginx:1.30.6-alpine` | 1.30이 stable 계열, 1.31은 mainline. 2026-11-06 갱신 |
| DB | `postgres:18` | 002에서 정함. 현재 18.6 |

Boot 계층 추출은 로컬 jar에서 `java -Djarmode=tools -jar server.jar help`로 `extract`·`list-layers` 명령이 있는 것을 확인했다. `extract --layers --launcher`의 정확한 옵션은 구현 때 `extract --help`로 다시 보고 dev-log에 적는다.

### 7. README

- "로컬 실행"을 둘로 나눈다. (1) 한 번에 띄우기: `.env` 복사 → `docker compose up -d --build` → 대시보드 `http://localhost:3000` → 키 발급 → 도구 base URL `http://localhost:8080/v1`. (2) 개발 모드: `docker compose up -d postgres` → `bootRun` → `npm run dev`(지금 내용).
- "도구 연결" 절: OpenAI 호환 클라이언트(base URL + API 키), Anthropic 호환 클라이언트(`ANTHROPIC_BASE_URL`), curl 예시 둘, `X-Tollgate-Tag` 헤더, 요청 ID 헤더. 실제 공급처를 쓰려면 `.env`의 키만 채우면 된다는 점.
- "스모크와 CI" 절: `scripts/smoke.sh` 실행법과 CI가 도는 것(배지 하나).
- 상단 "상태" 절에 1단계 완료 기준 1·2·4·5 충족을 표시(3은 012).

### 8. 검증

- 로컬: `docker compose up -d --build` 뒤 `docker compose ps`로 네 서비스 healthy, `scripts/smoke.sh` PASS, 대시보드(3000)에서 로그인·개요·요청 목록 확인(스모크가 만든 기록이 보여야 함), nginx 경유 스트리밍을 `curl -N`으로 눈으로 확인, `docker compose down` 뒤 `up`에서 데이터 유지(볼륨). 이미지 크기와 빌드 시간을 재어 기록한다.
- CI: PR을 올려 세 잡이 녹색인지 확인. 첫 실행이라 실패하면 로그를 보고 고친다(CLAUDE.md의 PR 모니터링 규칙).
- 서버·대시보드 코드는 바꾸지 않으므로 기존 테스트 수(서버 128, 가짜 공급처 40, 대시보드 28)는 그대로다.

### 9. 승인이 필요한 항목

1. 베이스 이미지 다섯 가지와 태그(6절). alpine 계열 Temurin·nginx·node, postgres는 그대로.
2. Dockerfile 세 개 모두 빌드 컨텍스트를 저장소 루트로 두고 `.dockerignore`를 추가. JVM 둘은 같은 Gradle 스테이지를 각자 가진다(파일을 하나로 합쳐 `ARG`로 가르는 대신 읽기 쉬운 쪽).
3. compose에서 PostgreSQL 호스트 포트를 계획 4.7과 달리 그대로 노출(개발 흐름 유지). 컨테이너 간 주소는 compose에 고정하고 `.env`는 호스트 값만.
4. nginx가 `/admin/`과 `/v1/` 둘 다 프록시(SSE용 버퍼링 끔). README의 기본 연결은 8080 직접.
5. 스모크는 bash + curl + python(JSON)으로, 네 번 호출 + nginx 경유 한 번 + 기록 확인. 기본은 데이터를 남기고 `SMOKE_DOWN=1`로만 정리.
6. GitHub Actions 세 잡(서버, 대시보드, 스모크)을 PR·main push에서 실행. 액션은 정확한 버전 태그로 고정, SHA 고정은 하지 않음. 이미지 푸시·배포 없음.
7. README 재구성(7절)과 CI 배지 추가.

## 확인한 사실 (2026-11-08)

- 서버는 `TOLLGATE_DB_HOST`·`TOLLGATE_DB_PORT`·`POSTGRES_DB`·`POSTGRES_USER`·`POSTGRES_PASSWORD`로 DB에 붙고, `FAKE_PROVIDER_URL`·`OLLAMA_BASE_URL`·`OPENAI_API_KEY`·`ANTHROPIC_API_KEY`·`TOLLGATE_PRICES_FILE`·`SERVER_PORT`를 읽는다(`server/src/main/resources/application.yml`). 액추에이터는 `health`만 노출하고 컴포넌트를 보여 준다. 가짜 공급처는 `FAKE_PROVIDER_PORT`·`FAKE_API_KEY`·`FAKE_*` 기본값을 읽고 `health`를 노출한다(`fake-provider.yml`).
- 현재 `docker-compose.yml`은 `postgres`만 있고 `pgdata` 볼륨과 `pg_isready` 헬스체크를 가진다. `scripts/`, `.github/`, Dockerfile, `.dockerignore`는 없다. `.gitattributes`가 `eol=lf`를 강제하므로 셸 스크립트의 줄 끝은 안전하다.
- Gradle 래퍼 9.8.2(`gradle-wrapper.properties`), 서버 `bootRun`의 작업 디렉터리는 저장소 루트, 단가 파일은 `config/prices.example.yaml`.
- Docker Hub 태그와 갱신일은 `hub.docker.com/v2/repositories/library/<이미지>/tags` API로, GitHub Actions 최신 릴리스는 `gh api repos/<owner>/<repo>/releases/latest`로 읽었다. `actions/setup-java` README에 `java-version: '25'` 예시와 "GitHub 호스트 러너는 Temurin을 미리 캐시한다"는 설명이 있다.

## 결정과 이유

- `config/`는 이미지에 복사하지 않고 `./config:/app/config:ro`로 마운트했다(명세 2절의 "이미지에 복사"에서 바꿈). 서버 컨테이너의 작업 디렉터리가 `/app`이라 `.env`의 `TOLLGATE_PRICES_FILE=config/prices.example.yaml`이 호스트의 `bootRun`과 컨테이너에서 똑같이 풀리고, 요금표 파일을 고치면 재빌드 없이 재시작만으로 반영된다.
- compose의 `TOLLGATE_ADMIN_TOKEN`은 `${...:-}`(빈 기본값)이다. `${...:?}`로 하면 `.env` 없이 `docker compose up -d postgres`(개발 모드)까지 막힌다. 토큰이 비면 서버가 기동 때 거절하고 로그에 이유를 남기므로 그쪽에 맡겼다.
- 컨테이너 안 포트는 8080·9090·80으로 고정하고 `.env`의 `SERVER_PORT`·`FAKE_PROVIDER_PORT`·`DASHBOARD_PORT`는 호스트 매핑에만 쓴다. 가짜 공급처는 `FAKE_PROVIDER_PORT`로 자기 포트를 정하므로 그 변수를 컨테이너에 넘기면 안 된다.
- nginx는 `resolver 127.0.0.11`과 변수 `proxy_pass`로 서버 컨테이너 이름을 요청 때마다 다시 푼다. 고정 `proxy_pass http://server:8080`은 기동 때 한 번만 풀어 서버 컨테이너를 다시 만들면 502가 난다. `ipv6=off`는 compose 네트워크가 IPv4뿐이라서다.
- 헬스체크는 `localhost` 대신 `127.0.0.1`을 부른다(겪은 문제 1).
- 스모크 스크립트는 `.env`를 읽을 때 CR을 떼어 Windows에서 편집된 파일도 받고, `python3`·`python` 후보를 실제로 실행해 보고 고른다(겪은 문제 2). CI는 실행 비트에 기대지 않고 `bash scripts/smoke.sh`로 부른다. Windows 작업 트리에는 실행 비트가 없으므로 커밋 때 `git update-index --chmod=+x`로 올린다.
- Boot 계층 추출 명령은 `extract --layers --launcher --destination extracted`다. `--help`가 없고 잘못된 옵션을 주면 사용법이 찍히는데, 거기서 `--layers`(목록, 비우면 전부)·`--launcher`·`--destination`을 확인했다. 계층은 `dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application` 네 개(`list-layers`).
- 액션은 `actions/upload-artifact@v7.0.2`(2026-10-07)도 정확한 태그로 고정했다. 테스트 리포트는 실패 때만 올린다.

## 겪은 문제

1. 첫 `docker compose up --wait`에서 대시보드만 `unhealthy`였다. 호스트에서는 3000이 200인데 컨테이너 안의 `wget http://localhost/`가 "Connection refused"였다. busybox `wget`이 `localhost`를 `::1`로 먼저 풀고 nginx는 `listen 80`(IPv4)뿐이라서다. 세 Dockerfile의 헬스체크를 `127.0.0.1`로 바꿨다.
2. 스모크가 "project or key" 단계에서 빈 값으로 실패했다. Windows의 `python3`가 Microsoft Store 설치 안내 스텁(`WindowsApps/python3`)이라 `command -v`에는 잡히지만 실행하면 안내문만 찍는다. 후보를 실제로 `-c "import json"`으로 실행해 보고 고르게 했다.
3. 워크플로 YAML(`ci.yml`)은 로컬에서 검사하지 못했다. PyYAML이 없고 `js-yaml`도 대시보드 의존성에 없으며, 일회성 `npx` 실행은 이 세션의 권한에서 막혔다. compose 파일은 `docker compose config`가 검사했고, `ci.yml`의 문법·의미 검증은 PR의 첫 CI 실행에 맡긴다.

## 실행한 검증과 결과 (2026-11-08)

- `docker compose config --quiet` 통과(compose 파일 검증). `ci.yml`은 로컬에서 검사하지 못했다(겪은 문제 3).
- 첫 이미지 빌드(`docker compose build`, Docker Desktop 29.9.2, WSL2): Gradle 스테이지 서버 72.4초, 가짜 공급처 55.7초(둘이 병렬, `/root/.gradle` 캐시 마운트 공유), 대시보드 npm 빌드 수 초. 결과 이미지 `token-tollgate-server` 418MB, `token-tollgate-fake-provider` 394MB, `token-tollgate-dashboard` 93.6MB, `postgres:18` 650MB.
- `bash scripts/smoke.sh`(Git Bash, 이미지 캐시 상태): 13단계 모두 PASS, 7초. 네 번의 중계와 nginx 경유 한 번이 모두 200이고 요청 ID가 기록에 `outcome=ok`·비용과 함께 있었다. `SMOKE PASSED`, 종료 코드 0.
- nginx 경유 스트리밍 버퍼링 확인: `fake-small:out=5,gap=400`을 `localhost:3000/v1/chat/completions`로 보내 줄마다 도착 시각을 찍었더니 0.00·0.40·0.80·1.20·1.60초로 조각마다 바로 왔다. 버퍼링되면 1.6초에 한꺼번에 왔을 것이다.
- 브라우저(앱 내장, 1280×900): `http://localhost:3000`에서 `.env.example`의 토큰으로 로그인(nginx 경유 `/admin`), 개요에 스모크 프로젝트가 요청 5건·비용 0.000928로 보이고, 요청 목록 `tag=smoke`에 5행(비용·TTFT·소요 포함). 콘솔 오류 없음. 캡처는 저장소에 두지 않는다.
- `docker compose down` → `docker compose up -d --wait`: 캐시된 이미지로 네 서비스 healthy까지 17초. 스모크 기록 5건이 볼륨에 그대로 남았다.
- 서버·대시보드 코드는 바꾸지 않아 테스트는 다시 돌리지 않았다(서버 128, 가짜 공급처 40, 대시보드 28은 010 시점 그대로). CI의 첫 실행은 이 작업의 PR에서 한다.

## 남은 문제

- CI 세 잡의 실제 소요 시간은 PR의 첫 실행 뒤에 적는다. 스모크 잡은 Gradle 캐시 없이 이미지 셋을 빌드하므로 몇 분 걸릴 것이다.
- JVM 이미지가 400MB 안팎이다. `jlink`로 줄인 런타임이나 의존성 정리는 나중 과제다.
- `docker compose up --wait`는 compose v2가 필요하다(이 PC는 v5.6.1). 아주 오래된 compose는 `--wait`를 모른다.
- 재시작 정책·TLS·멀티 아키텍처 빌드는 두지 않았다(명세의 "하지 않는 것").
- 스모크가 만든 `smoke-*` 프로젝트는 실행할 때마다 하나씩 쌓인다. `SMOKE_DOWN=1`이 아니면 남으므로 로컬에서는 가끔 `docker compose down -v`로 비운다.
