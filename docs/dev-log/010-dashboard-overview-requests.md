# 010 대시보드 2: 개요, 요청 목록

- 명세 작성·승인·구현·검증: 2026-11-07
- 선행: 008 사용량 조회 API(`usage/summary`, `usage/records`), 009 대시보드 뼈대(로그인, 레이아웃, API 클라이언트, 해시 라우터, 테스트 도우미)
- 후속: 011이 compose·nginx로 대시보드 빌드 산출물을 서빙한다. 012가 Ollama 기록이 쌓이는 것을 이 화면에서 확인한다

## 목표

사용량 기록을 보는 두 화면을 009의 뼈대 위에 올린다. 개요 화면은 기간을 고르면 총 비용·요청·토큰 카드, 일별 추이 차트, 프로젝트·모델·태그별 분해 표를 보여 준다. 요청 목록 화면은 최근 요청을 필터와 "더 보기"로 넘겨 본다. 브리프의 완료 기준 4("기간·프로젝트·모델·태그별 비용을 볼 수 있다")를 이 작업으로 채운다.

## 완료 기준

1. 개요: 기간(오늘·7일·30일·직접 입력)과 프로젝트·공급처·태그 필터를 바꾸면 카드·차트·분해 표가 같은 조건으로 다시 조회된다. 일별 차트는 기록 없는 날도 0으로 채운다. 차트에는 표 보기가 있다.
2. 요청 목록: 같은 필터에 결과(outcome)·모델 필터를 더해 50건씩 보여 주고, "더 보기"가 `nextBefore` 커서로 다음 50건을 이어 붙인다. 행을 펼치면 요청 ID 등 상세가 보인다.
3. 필터 상태는 주소의 해시 쿼리(`#/overview?range=7d&projectId=…`)에 있어 새로 고침·뒤로 가기·공유가 된다. 분해 표의 행에서 요청 목록으로 그 조건을 들고 넘어간다.
4. 기본 화면이 개요가 되고 왼쪽 탭 순서는 개요, 요청 목록, 키 관리, 요금표다.
5. `npm run build`, `npm run lint`, `npm test` 통과. 새 테스트 10개 안팎(합계 26개 안팎). Recharts를 더한 뒤 빌드 크기를 재어 기록한다.
6. 가짜 공급처로 기록을 수십 건 만든 뒤 브라우저에서 기간·필터·탭·더 보기를 확인한다.

## 명세

### 1. 구조 (`dashboard/src`, 새 파일과 바뀌는 파일)

| 파일 | 역할 |
|---|---|
| `api/types.ts` | `UsageMetrics`, `UsageGroup`, `UsageSummary`, `UsageRecord`, `UsageRecordsPage`, `Outcome` 타입 추가(008 응답과 1:1) |
| `api/admin.ts` | `usageSummary(params)`, `usageRecords(params)` 추가. 파라미터 객체를 쿼리 문자열로 바꾸는 작은 함수 하나 |
| `router/useHashRoute.ts` | 해시의 쿼리 문자열을 `URLSearchParams`로 읽고 쓰는 `[route, query, navigate(route, query?)]`로 확장. `DEFAULT_ROUTE`를 `overview`로 |
| `layout/Shell.tsx` | 탭 순서: 개요, 요청 목록, 키 관리, 요금표 |
| `usage/period.ts` | 기간 프리셋 → `from`/`to`(2절), 브라우저 시간대, 일별 묶음에 빈 날짜 0 채우기, 직접 입력 검증 |
| `usage/filters.ts` | 해시 쿼리 ↔ 필터 객체 변환(`range`, `from`, `to`, `projectId`, `provider`, `tag`, `model`, `outcome`) |
| `usage/UsageFilterBar.tsx` | 두 화면이 함께 쓰는 필터 줄(기간 프리셋 + 직접 입력, 프로젝트, 공급처, 태그; 요청 목록에서는 결과·모델 추가) |
| `components/StatTile.tsx` | 값 + 라벨 + 보조 줄의 카드. 큰 숫자는 비례 폭 글자(표가 아니므로 `tabular-nums`를 쓰지 않음) |
| `components/Segmented.tsx` | 차트 지표(비용·요청·토큰)와 분해 축(프로젝트·모델·태그) 전환용 버튼 묶음(`role="tablist"`) |
| `pages/overview/OverviewPage.tsx` | 3절. 필터 → 카드 → 일별 차트 → 분해 표 |
| `pages/overview/DailyChart.tsx` | Recharts 영역 차트 한 계열 + 표 보기 |
| `pages/overview/BreakdownTable.tsx` | 묶음별 표 + 비율을 나타내는 순위 막대(CSS) |
| `pages/requests/RequestsPage.tsx`, `RequestTable.tsx` | 4절 |
| `format.ts` | `formatMs`(845 → "845 ms", 1830 → "1.8 s"), `formatPercent`, `formatTokens`(=`formatCount`) 추가 |
| `index.css` | 필터 줄, 카드 줄, 차트 영역, 순위 막대, 결과 배지, 펼침 행 스타일 |

### 2. 기간과 시간대

- 시간대는 브라우저의 `Intl.DateTimeFormat().resolvedOptions().timeZone`(예: `Asia/Seoul`)을 `tz`로 보낸다. 필터 줄에 "시간대 Asia/Seoul"을 작게 보여 준다. 저장은 UTC이고 일별 묶음만 이 시간대로 계산한다는 008 규칙 그대로다.
- 프리셋은 날짜 문자열(`YYYY-MM-DD`)로 보낸다. 서버가 날짜를 `tz`의 자정으로 해석하고 `from` 포함·`to` 제외이므로:

| 프리셋 | `from` | `to` |
|---|---|---|
| 오늘 (`range=today`) | 오늘 | 내일 |
| 7일 (`range=7d`, 기본) | 오늘 − 6일 | 내일 |
| 30일 (`range=30d`) | 오늘 − 29일 | 내일 |
| 직접 (`range=custom&from=…&to=…`) | 입력한 시작일 | 입력한 종료일 + 1일(화면에는 종료일을 포함으로 보여 줌) |

- 직접 입력 검증은 서버와 같은 규칙으로 먼저 막는다: 시작일 < 종료일, 366일 이하.
- 일별 차트는 `from`부터 `to` 전날까지 날짜를 모두 만들고 응답의 `groups`(key = `YYYY-MM-DD`)를 채워 넣는다. 없는 날은 0이다(008 남은 문제).

### 3. 개요 화면 (`#/overview`)

위에서 아래로 한 줄의 필터, 카드 네 장, 일별 차트, 분해 표. 필터는 아래 전부를 같은 조건으로 묶는다(차트 안에 필터를 두지 않는다).

- 필터 줄: 기간 프리셋(오늘·7일·30일·직접), 직접일 때 시작·종료 날짜 입력, 프로젝트(`listProjects(true)`의 이름, 보관된 것도 포함), 공급처(`listProviders`), 태그(정확 일치 텍스트). 바꾸면 해시 쿼리를 고치고, 쿼리가 바뀌면 조회한다. 다시 읽는 동안 이전 화면을 흐리게 유지한다(골격 화면이나 깜빡임 없음).
- 조회: `usageSummary({groupBy: 'day'})` 한 번(카드의 `totals`와 차트), 분해 탭의 `usageSummary({groupBy: 'project' | 'model' | 'tag'})` 한 번. 탭을 바꾸면 그 묶음만 다시 조회한다.
- 카드: 총 비용(USD, 큰 숫자), 요청 수(보조 줄 "성공 n · 실패 n"), 입력 토큰(보조 줄 "캐시 n 포함"), 출력 토큰. 비용 카드의 보조 줄은 `priceMissingRequests`나 `usageUnknownRequests`가 0보다 크면 "단가 없음 n건 · 사용량 모름 n건"을 경고색 글자로 보여 준다. 숫자는 천 단위 구분, 비용은 `formatUsd`.
- 일별 차트: 한 계열의 영역 차트(Recharts `AreaChart` + `Area` + `XAxis` + `YAxis` + `Tooltip` + `ResponsiveContainer`). 지표 전환 버튼(비용 · 요청 · 토큰)으로 같은 축에 한 계열만 그린다. 축 두 개짜리 차트는 만들지 않는다. 격자와 축은 실선 머리카락 선, 선 2px, 강조색 한 가지(`--accent`), 호버는 세로 기준선 + 툴팁(날짜와 그 지표). 차트 아래 "표로 보기"를 켜면 날짜·비용·요청·입력·캐시·출력 토큰 표가 나온다(툴팁 없이도 모든 값에 닿게). 기록이 없으면 0 선과 "이 기간에 기록이 없습니다" 문구.
- 분해 표: 탭(프로젝트 · 모델 · 태그). 열은 이름, 요청(성공/전체), 입력, 캐시, 출력, 비용, 비율(기간 총 비용 대비). 비용 내림차순, 비율 열에 한 가지 색의 얇은 가로 막대(CSS `div`, 축이 하나뿐이라 차트 라이브러리 불필요). 이름: 프로젝트는 `label`(이름), 모델은 `provider/key`, 태그는 `key`(null이면 "태그 없음"). 행의 이름을 누르면 요청 목록(`#/requests`)으로 같은 기간·필터에 그 프로젝트/모델/태그를 더해 이동한다.
- 가짜 공급처의 실패 기록은 지시가 붙은 모델명(`fake-small:err=1`)으로 따로 묶인다(008 남은 문제). 화면은 서버가 묶은 키를 그대로 보여 준다. 가짜 공급처에서만 생기는 일이고, 떼어 내면 서버 집계와 화면이 달라진다.

### 4. 요청 목록 화면 (`#/requests`)

- 필터 줄: 개요와 같은 항목 + 결과(전체 · ok · upstream_error · client_abort · timeout · gateway_error) + 모델(정확 일치 텍스트).
- 조회: `usageRecords({…filter, limit: 50})`. 필터가 바뀌면 첫 쪽부터, "더 보기"는 `before = nextBefore`로 다음 50건을 뒤에 붙인다. `nextBefore`가 null이면 버튼을 숨기고 "끝"을 표시한다.
- 표 열: 시각(브라우저 시간대, `formatDateTime`), 프로젝트(이름, 아래 작게 키 접두사), 태그, 모델(`modelServed ?? modelRequested`, 공급처를 앞에 작게), 토큰(입력 · 캐시 · 출력), 비용(`formatUsd`, null이면 "—"), TTFT(`formatMs`, 비스트림이면 "—"), 소요, 결과(배지: ok 초록, client_abort 회색, 나머지 빨강, 옆에 HTTP 상태), 스트림(예/아니오). 비용이 null인 행은 비용 칸에 이유 툴팁("단가 없음" 또는 "사용량 모름", `usageSource`로 구분).
- 행 끝 "자세히"를 누르면 아래에 펼침 행: 요청 ID(`x-tollgate-request-id`와 같은 값, 복사 버튼), 키 ID, 프로토콜, 요청 모델/응답 모델, 사용량 출처(`response`/`stream`/`none`), 오류 종류(`errorType`).
- 비어 있으면 "조건에 맞는 요청이 없습니다".

### 5. 라우터와 해시 쿼리

- `#/overview?range=7d&projectId=…&provider=fake&tag=dev`, `#/requests?range=today&outcome=upstream_error&model=fake-small`. `useHashRoute`가 경로 이름과 `URLSearchParams`를 함께 돌려주고, `navigate(route, query)`가 해시를 바꾼다. 값이 기본(`range=7d`, 빈 필터)이면 쿼리에서 뺀다.
- 009의 `parseRoute`는 이미 `?` 뒤를 무시하므로 키 관리·요금표는 영향이 없다.

### 6. 새 런타임 의존성 (npm 레지스트리에서 2026-11-07 확인)

| 패키지 | 버전 | 공개일 | 고른 이유 |
|---|---|---|---|
| `recharts` | 3.10.1 | 2026-07-25 | `latest` 태그. 3.11은 canary만 있다. peer `react ^19`, `react-dom ^19`, `react-is ^19`. 계획 4.6절의 선택(선언적 React 컴포넌트, 타입 내장, 선·막대만 필요)이 그대로 맞다 |

- peer `react-is`는 설치 때 React 19.3.1과 같은 버전으로 들어간다(npm이 peer를 자동 설치). `package.json`에는 `recharts`만 정확한 버전으로 적는다.
- 차트는 일별 추이 하나뿐이다. 분해의 비율 막대는 CSS로 그린다. Recharts가 번들에 더하는 크기는 빌드 뒤 재어 dev-log에 적는다(009 기준 JS 247 kB, gzip 77 kB).

### 7. 디자인

- 009의 토큰과 글꼴을 그대로 쓴다. 구현 때 `frontend-design`과 `dataviz` 지침을 따른다.
- 카드 줄: 네 장을 한 줄에, 큰 숫자 하나와 작은 보조 줄. 이 화면에서 힘을 주는 곳은 총 비용 숫자 하나다.
- 차트 색: 계열 한 가지는 `--accent`(#0b6e99), 격자는 `--line`, 글자는 잉크 토큰(계열 색을 글자에 쓰지 않음). 어두운 모드는 같은 토큰의 어두운 단계. 구현 때 `dataviz`의 `validate_palette.js`로 밝은·어두운 바탕에서 검사하고 결과를 dev-log에 적는다.
- 결과 배지는 상태색(`--ok`, `--danger`, 중립)에 글자를 함께 둔다. 색만으로 뜻을 전하지 않는다.
- 접근성: 지표·분해 탭은 `role="tablist"`와 키보드 좌우 이동, 차트에는 표 보기, 펼침 행은 `aria-expanded`.
- 900px 아래: 카드 2×2, 표는 009처럼 패널 안 가로 스크롤.

### 8. 테스트

009의 `FakeAdminApi` 경로표를 그대로 쓴다. Recharts는 jsdom에서 크기가 0이라 SVG를 그리지 않으므로, 차트 테스트는 "표로 보기"의 표를 검사한다.

| 파일 | 내용 |
|---|---|
| `usage/period.test.ts` (3) | 프리셋 → `from`/`to`(오늘·7일·직접 +1일), 빈 날짜 0 채우기, 직접 입력 검증(순서·366일) |
| `usage/filters.test.ts` (1) | 해시 쿼리 ↔ 필터 객체 왕복, 기본값 생략 |
| `router/useHashRoute.test.ts` (+1) | 쿼리가 있는 해시에서 경로와 파라미터 |
| `pages/overview/OverviewPage.test.tsx` (3) | 카드 숫자와 경고 줄, 프리셋 변경이 `from`/`to`/`tz`를 바꿔 다시 조회, 분해 탭 변경이 `groupBy`를 바꾸고 표를 그림 |
| `pages/requests/RequestsPage.test.tsx` (3) | 행 렌더(배지·비용 "—"), 결과 필터가 `outcome` 쿼리로, 더 보기가 `before=nextBefore`로 이어 붙임 |
| `format.test.ts` (+1) | `formatMs`, `formatPercent` |

합계 새로 12개 안팎, 전체 28개 안팎.

### 9. 수동 확인

1. Docker Desktop·compose PostgreSQL·가짜 공급처(`fake-provider` jar, 9090)·서버(`bootRun`, 관리자 토큰)·`npm run dev`.
2. 대시보드에서 프로젝트 두 개에 키를 발급한 뒤, PowerShell 스크립트로 OpenAI·Anthropic 호환 경로에 60건쯤 보낸다: 모델 `fake-small`·`fake-large`, 태그 둘, 스트림·비스트림, `err=1`·`abort=1` 지시 몇 건. 유료 공급처는 부르지 않는다.
3. 개요: 7일 → 오늘 → 직접 입력으로 카드·차트가 바뀌는지, 지표 전환, 표로 보기, 분해 탭 셋, 프로젝트 필터, 행 클릭으로 요청 목록 이동.
4. 요청 목록: 결과 필터(`upstream_error`, `client_abort`), 더 보기로 두 번째 쪽, 펼침 행의 요청 ID, 새로 고침 뒤 필터 유지, 뒤로 가기.
5. 밝은·어두운 모드와 375px 폭 캡처. 결과와 캡처를 dev-log에 적는다.

### 10. 승인이 필요한 항목

1. 런타임 의존성 `recharts` 3.10.1 추가(6절). 이 프로젝트의 첫 런타임 의존성이고 peer `react-is`가 함께 들어간다.
2. 기본 화면을 개요로 바꾸고 탭 순서를 개요·요청 목록·키 관리·요금표로(완료 기준 4).
3. 필터 상태를 해시 쿼리에 두는 방식(5절). 새로 고침·뒤로 가기·공유가 되지만 주소에 프로젝트 ID와 태그가 보인다(키·토큰은 들어가지 않음).
4. 차트는 한 계열 영역 차트에 지표 전환(비용·요청·토큰)으로 하고 축 두 개짜리는 만들지 않는다. 분해는 표 + CSS 순위 막대로 하고 차트 라이브러리를 쓰지 않는다(3절).
5. 기간 프리셋의 뜻: 오늘 = 브라우저 시간대의 오늘 자정부터, 7일·30일 = 오늘 포함 달력일, 직접 = 종료일 포함(2절).
6. 요청 목록은 50건씩 "더 보기"로 이어 붙이고 행 펼침으로 상세를 본다(4절). 쪽 번호 넘기기는 두지 않는다.
7. 가짜 공급처의 지시 붙은 모델명은 서버가 묶은 그대로 보여 준다(008 남은 문제 결정).

## 확인한 사실 (2026-11-07)

- 사용량 API 형식은 서버 코드(`admin/UsageDtos.kt`, `admin/UsageController.kt`, `usage/UsageQuery.kt`)에서 확인했다. `summary` 응답: `from`, `to`, `tz`, `groupBy`, `totals{requests, okRequests, inputTokens, cachedInputTokens, outputTokens, cost, priceMissingRequests, usageUnknownRequests}`, `groups[{key, label, provider, …같은 지표}]`. `records` 응답: `records[{id, requestId, startedAt, projectId, projectName, keyId, keyPrefix, tag, protocol, provider, modelRequested, modelServed, inputTokens, cachedInputTokens, outputTokens, cost, usageSource, ttftMs, durationMs, httpStatus, streamed, outcome, errorType}]`, `nextBefore`. 파라미터: `from`·`to`(날짜 또는 instant, `from` 포함·`to` 제외, 기본 최근 7일, 최대 366일), `tz`, `projectId`, `tag`, `model`, `provider`, `groupBy`(day·project·model·tag), `records`에만 `outcome`·`limit`(1~200, 기본 50)·`before`.
- `outcome` 값은 DB 제약 그대로 `ok`, `upstream_error`, `client_abort`, `timeout`, `gateway_error`. `usage_source`는 `response`, `stream`, `none`. `errorType`은 상류 오류 종류, `http_<상태>`, `stream_error`, `stream_interrupted`, 또는 게이트웨이 오류 코드다.
- Recharts 버전·공개일·peer는 `npm view recharts version time peerDependencies dist-tags`로 읽었다(`latest` 3.10.1, 2026-07-25; 3.11.0은 canary 9까지만).

## 결정과 이유

- 라우터는 `[{route, search}, navigate(route, search?)]`로 넓혔다. `search`는 `?` 뒤의 문자열이고, 각 화면이 `parseFilters`로 읽어 `serializeFilters`로 다시 쓴다. 필터를 React 상태가 아니라 주소에 두므로 새로 고침·뒤로 가기·링크 공유가 그대로 된다. 기본값(`range=7d`, 빈 필터)은 쿼리에서 빼 주소를 짧게 유지한다.
- 조회 결과는 요청 조건의 키(`JSON.stringify(params)`)와 함께 저장하고, 렌더에서 "지금 조건의 키와 다르면 stale"을 파생해 이전 화면을 흐리게 유지한다(`.is-refreshing`). 골격 화면이나 깜빡임이 없고, 009와 같이 effect 안에서 동기 `setState`를 하지 않아 lint 경고가 없다. 늦게 온 응답은 `cancelled` 플래그로 버린다.
- Recharts는 `React.lazy`로 `DailyChart`만 따로 불러온다. 설치 직후 메인 번들이 247 kB에서 635 kB(gzip 189 kB)로 커져 Vite가 500 kB 경고를 냈다. 나눈 뒤 메인 268.6 kB(gzip 82.7 kB) + 차트 청크 367.9 kB(gzip 106.1 kB)다. 개요가 기본 화면이라 첫 화면에서 두 청크를 다 받지만, 키 관리·요금표만 쓰는 흐름과 캐시에는 이득이 있다.
- 차트 색은 `--chart-series` 토큰 하나다. `dataviz` 스킬의 `validate_palette.js`로 검사했더니 밝은 모드의 강조색 `#0b6e99`는 통과했지만 어두운 모드의 강조색 `#63b3e4`는 어두운 바탕용 명도 띠(OKLCH L 0.48~0.67)를 벗어났다(L 0.735). 어두운 모드 차트에는 `#3a94c9`(명도·채도·대비 모두 통과)를 쓰고, 버튼·링크의 강조색은 그대로 둔다. 상태색 네 가지를 범주형 팔레트로 넣으면 인접 쌍(빨강↔황토)이 CVD 구분에서 떨어지지만, 상태색은 글자가 함께 있는 배지에만 쓰고 차트 계열로는 쓰지 않으므로 그 검사는 해당하지 않는다.
- 요청 목록의 열은 명세의 11개를 9개로 줄였다. TTFT와 소요를 "845 ms / 1.2 s" 한 열로, 스트림 여부는 결과 칸의 "200 · 스트림"으로 합쳤고, "자세히" 버튼을 첫 열에 두어 가로 스크롤 상태에서도 보이게 했다. 시각은 `formatLogTime`(`2026-11-07 18:35:12`, 브라우저 시간대)로 초까지 보여 준다. 1280px에서도 표가 패널 안에서 가로로 스크롤한다.
- 직접 입력 기간은 두 날짜가 비어 있으면 오류 대신 안내("시작일과 종료일을 고르면 그 기간을 조회합니다")를 보이고, 한쪽이라도 채워지면 서버와 같은 규칙으로 검증한다. 검증을 통과하기 전에는 조회하지 않고 이전 화면을 흐리게 둔다.
- 분해 표의 이름을 누르면 요청 목록으로 가면서 프로젝트는 `projectId`, 모델은 `provider`+`model`, 태그는 `tag`를 더한다. 태그 없음 행은 서버에 "태그 없음" 필터가 없어 링크를 두지 않았다.
- 텍스트 필터(태그, 모델)는 Enter나 포커스 이탈 때만 반영한다(`CommitText`). 글자마다 조회하면 요청이 쏟아지고 주소가 계속 바뀐다. 커밋된 값이 바뀌면 `key`로 다시 마운트해 effect 없이 초안을 맞춘다.

## 겪은 문제

1. `recharts`를 설치하니 peer `react-is`가 17.0.2로 묶였다(`@testing-library/dom`의 `pretty-format`이 끌어온 것을 npm이 재사용). Recharts가 `react-is`의 `isFragment`를 쓰는데 React 19 엘리먼트 표식과 다를 수 있어 `react-is` 19.3.1을 직접 의존성으로 고정했다. `npm ls react-is`로 recharts가 19.3.1을 보는 것을 확인했다.
2. 테스트에서 jsdom에 `ResizeObserver`가 없어 Recharts `ResponsiveContainer`가 깨진다. `test/setup.ts`에 빈 스텁을 두고, 차트 테스트는 SVG 대신 "표로 보기"의 표를 검사한다.
3. oxlint `react(use-memo)`: `useMemo(browserTimeZone, [])`처럼 함수 참조를 넘기면 경고한다. 인라인 화살표로 바꿨다.
4. 로그인 테스트가 "`GET /providers` 호출 1회"를 기대했는데 기본 화면이 개요로 바뀌어 개요가 공급처 목록을 한 번 더 부른다. 첫 호출의 토큰만 검사하도록 고쳤다.
5. 앱 내장 브라우저의 `find`가 분해 표의 링크("fake/fake-small")를 찾지 못했다. `read_page`로 ref를 얻거나 주소로 직접 이동해 확인했다. 앱 문제는 아니다.
6. 명세대로 11열을 두니 1280px에서 셀이 두세 줄로 접혔다("2026. 11. 7. 오후 6:35", "아니오"). 열을 9개로 합치고 `white-space: nowrap` + 패널 가로 스크롤로 바꿨다(결정과 이유).
7. Docker Desktop이 또 꺼져 있었다(세션 시작 때마다). 샌드박스 밖에서 띄우고 `docker compose up -d postgres`.
8. `fake-small:err=1`은 상태 코드 1이 400~599 밖이라 가짜 공급처가 400으로 답했다. 기록 생성에는 상관없어(`upstream_error`로 남음) 그대로 두었다.

## 실행한 검증과 결과 (2026-11-07)

- `npm run lint`: oxlint 1.89.0, 경고 0.
- `npm run build`: `tsc -b` 통과. `index.html` 0.46 kB, CSS 11.38 kB(gzip 3.08 kB), 메인 JS 268.64 kB(gzip 82.74 kB), `DailyChart` 청크 367.89 kB(gzip 106.11 kB). 009 대비 메인 +21 kB, 차트 청크 신규.
- `npm test`: vitest 5.0.5, 10개 파일 28개 통과(2.78초). 새 테스트 12개: `usage/period.test.ts` 3, `usage/filters.test.ts` 1, `router/useHashRoute.test.ts` +1, `pages/overview/OverviewPage.test.tsx` 3, `pages/requests/RequestsPage.test.tsx` 3, `format.test.ts` +1.
- 코드 규모: `dashboard/src` 소스 3,702줄(ts·tsx·css, 테스트 제외, 009의 2,326줄에서 +1,376), 테스트와 도우미 798줄.
- 팔레트 검사(`dataviz/scripts/validate_palette.js`): 밝은 바탕 `#ffffff`에서 `#0b6e99` 명도·채도·대비 통과, 어두운 바탕 `#1b222b`에서 `#3a94c9` 통과(`#63b3e4`는 명도 띠 밖, `#4a9fd0`도 L 0.671로 탈락).
- 수동 확인 환경: Docker Desktop 29.9.2의 compose PostgreSQL, 가짜 공급처 jar(9090), 008 수동 확인에 쓴 서버 jar(`TOLLGATE_ADMIN_TOKEN`, `TOLLGATE_PRICES_FILE=config/prices.example.yaml`), `npm run dev`, 앱 내장 브라우저 1280×900. 서버 로그 ERROR 0건(WARN은 openai·anthropic 키 없음 2건).
- 기록 생성: 스크립트로 프로젝트 `manual-010-web`·`manual-010-batch`에 키를 발급하고 가짜 공급처로 60건을 보냈다. 정상 48건(`fake-small`·`fake-large`·`fake-relay`, OpenAI·Anthropic 경로, 태그 `dev`·`batch`·`review`·없음, 일부 스트림), `err=1` 4건(400 → `upstream_error`), 스트림 끊김 `abort=3` 3건, 단가 없는 `fake-unpriced` 2건, 클라이언트가 1초 뒤 끊은 스트림 2건(`client_abort`). 유료 공급처는 부르지 않았다. 최근 7일 집계: 요청 74건(이전 수동 확인 기록 포함), 성공 59, 단가 없음 14, 사용량 모름 15.

| 단계 | 결과 |
|---|---|
| 로그인 직후 | 개요가 기본 화면. 카드 4장(총 비용 0.02876, 요청 74 · 성공 59 · 실패 15, 입력 5,880 · 캐시 140, 출력 2,127), 비용 카드 아래 "단가 없음 14건 · 사용량 모름 15건" 경고 줄 |
| 일별 차트 | 11-01~11-07 일곱 점, 기록 없는 날 0, 크로스헤어 툴팁("0.000462 · 2026-11-03 · 비용 (USD)"), 지표 전환 비용→요청→토큰에 축이 함께 바뀜, 표로 보기 체크 시 7행 표 |
| 분해 탭 | 프로젝트(manual-010-web 64.8%, …), 모델(fake/fake-large 83.4%, `fake-small:err=1`·`err=429`·`err=529` 행이 따로 묶임, 단가 없는 fake-relay 0.00), 태그(batch, 태그 없음은 링크 없음, dev, manual, review) |
| 모델 행 → 요청 목록 | `#/requests?provider=fake&model=fake-small`로 이동, 필터 줄에 공급처 fake·모델 fake-small이 채워지고 36건 표시 |
| 결과 필터 | `upstream_error` 선택 → 주소에 `outcome=upstream_error`, 5건, "끝" 표시 |
| 더 보기 | 필터 없이 50건 → "더 보기" → 74건 표시, "끝" |
| 행 펼침 | 클라이언트 중단 행에서 요청 ID + 복사 버튼, 키 ID, 프로토콜, 모델, 사용량 출처, 오류 종류 |
| 오늘 프리셋 | 59건, 한 점짜리 차트, 주소 `range=today` |
| 프로젝트 필터 | manual-010-batch → 21건, 비용 0.009312, 분해에 한 행 |
| 직접 입력 | 탭 전환 직후 안내문, 11-01~11-07 입력 시 주소에 `from`·`to`가 실리고 차트가 그 기간으로 |
| 뒤로 가기 | `range=today&projectId=…`로 돌아오며 프리셋·선택 상자가 함께 복원 |
| 밝은·어두운 모드, 375px | 두 모드 모두 토큰 적용, 차트 계열색은 모드별로 다름. 좁은 폭에서 필터 줄이 접히고 카드가 2×2 |

화면 캡처는 저장소에 두지 않는다. 확인 중 만든 프로젝트 둘과 기록 60건은 로컬 DB에 남겨 두었다(012에서 Ollama 기록과 함께 보는 데 쓸 수 있다).

## 남은 문제

- 하루짜리 기간(오늘)은 점 하나만 찍혀 차트가 비어 보인다. 시간대별 묶음은 서버에 없으므로(008), 필요하면 008에 `groupBy=hour`를 더해야 한다.
- 요청 목록 표는 1280px에서도 가로 스크롤이 생긴다. 열을 더 줄이려면 프로젝트의 키 접두사나 토큰 세부를 펼침 행으로 옮겨야 한다.
- 복사 버튼(요청 ID)은 009와 같은 이유로 일반 브라우저에서 확인이 필요하다.
- 가짜 공급처 지시가 붙은 모델명은 서버가 묶은 대로 보인다(승인 항목 7). 실제 공급처에서는 생기지 않는다.
- 010에서 쓴 `useHashRoute` 확장으로 009 화면(키 관리·요금표)은 바뀌지 않았지만, 두 화면은 아직 해시 쿼리를 쓰지 않는다.
