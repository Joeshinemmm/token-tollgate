# 009 대시보드 1: 로그인, 키 관리, 요금표

- 명세 작성·승인·구현·검증: 2026-11-07
- 선행: 003 프로젝트·키 관리 API, 004 요금표 API, 006 공급처 목록 API(`GET /admin/v1/providers`, 로그인 검증에 사용)
- 후속: 010이 같은 뼈대(로그인·레이아웃·API 클라이언트·라우터) 위에 개요와 요청 목록 화면을 더한다. 011이 nginx로 `/admin`을 서버에 프록시한다

## 목표

001에서 만든 Vite 템플릿을 실제 대시보드로 바꾼다. 관리자 토큰 로그인, 공통 레이아웃, 관리 API 클라이언트, 화면 전환을 만들고 그 위에 두 화면을 올린다. 키 관리 화면에서 프로젝트를 만들고 키를 발급·폐기하며, 요금표 화면에서 단가를 보고 더하고 바꾼다. 테스트는 vitest와 Testing Library로 하고, 빌드·타입 검사·린트를 통과한다.

## 완료 기준

1. 관리자 토큰을 입력해 로그인하면 `sessionStorage`에 보관되고 모든 관리 API 호출에 `Authorization: Bearer`로 붙는다. 틀린 토큰은 화면에 오류로 보이고, 어느 호출이든 401이 오면 토큰을 지우고 로그인 화면으로 돌아간다.
2. 키 관리: 프로젝트 생성·이름 변경·보관(archive)·보관 해제, 프로젝트별 키 목록(이름, 접두사, 생성 시각, 마지막 사용, 활성/폐기 상태), 발급 모달에서 평문 키를 한 번 보여 주고 복사 버튼을 제공, 폐기는 확인 뒤 실행.
3. 요금표: 공급처·모델별 최신 단가 표와 이력, 단가 추가·교체 폼(`PUT`), 삭제(사용 기록이 참조하면 409 메시지).
4. `npm run build`(`tsc -b` + `vite build`), `npm run lint`(oxlint), `npm test`(vitest) 통과. 테스트 15개 안팎.
5. Vite 개발 서버 + `bootRun` 서버 + compose DB로 로그인 → 프로젝트 생성 → 키 발급·폐기 → 단가 추가·삭제 흐름을 브라우저에서 확인한다.

## 명세

### 1. 구조 (`dashboard/src`)

| 파일 | 역할 |
|---|---|
| `main.tsx`, `App.tsx` | 진입점. `App`은 인증 게이트(토큰 없으면 로그인 화면) + 해시 라우터 + 레이아웃 |
| `index.css` | 디자인 토큰(CSS 변수, 밝은/어두운 모드 `prefers-color-scheme`), 리셋, 공통 요소 스타일. UI 라이브러리 없음 |
| `api/types.ts` | 서버 DTO와 1:1인 타입: `Project`, `VirtualKey`, `IssuedKey`, `Price`, `Provider`, `AdminError` |
| `api/client.ts` | `fetch` 래퍼. base `/admin/v1`, 토큰 헤더, JSON 직렬화, 오류를 `ApiError(status, code, message)`로 변환, 401이면 `onUnauthorized` 콜백 |
| `api/admin.ts` | 엔드포인트 함수: `listProviders`, `listProjects(includeArchived)`, `createProject`, `updateProject`, `listKeys(projectId)`, `issueKey`, `revokeKey`, `listPrices`, `upsertPrice`, `deletePrice` |
| `auth/AuthContext.tsx` | 토큰 상태와 `login(token)`(`GET /providers`로 검증)·`logout()`. 보관 키 `tollgate.adminToken`(sessionStorage) |
| `router/useHashRoute.ts` | `#/keys`, `#/prices`(010에서 `#/overview`, `#/requests` 추가). `hashchange` 구독, 기본 경로는 009에서 `#/keys` |
| `layout/Shell.tsx` | 왼쪽 탭(키 관리, 요금표), 위쪽에 서비스 이름과 로그아웃 |
| `components/` | `Modal`, `ConfirmDialog`, `Alert`, `CopyButton`, `Field`(라벨+입력+오류), `Badge` |
| `pages/LoginPage.tsx` | 토큰 입력(`type="password"`), 제출, 오류 표시 |
| `pages/keys/KeysPage.tsx`, `ProjectList.tsx`, `KeyTable.tsx`, `IssueKeyDialog.tsx` | 2절 |
| `pages/prices/PricesPage.tsx`, `PriceTable.tsx`, `PriceForm.tsx` | 3절 |
| `format.ts` | `formatUsd`(소수 2~8자리, 뒤의 0 제거), `formatDateTime`(브라우저 시간대, `ko-KR`), `formatRelative`(마지막 사용 "n분 전") |
| `test/setup.ts`, `test/fakeApi.ts` | jest-dom 매처 등록, `fetch`를 경로표로 흉내 내는 도우미 |

템플릿 자산(`App.css`, `assets/hero.png`, `react.svg`, `vite.svg`, `public/icons.svg`)은 지우고 `public/favicon.svg`만 우리 것으로 바꾼다. `index.html`은 `lang="ko"`, 제목 `token-tollgate`.

### 2. 키 관리 화면 (`#/keys`)

- 왼쪽: 프로젝트 목록(이름, 생성일, 보관 여부). "보관된 프로젝트 보기" 토글(`includeArchived`). 맨 위 "새 프로젝트" 입력 + 버튼(`POST /projects`). 이름 클릭으로 선택, 연필 아이콘으로 이름 변경(`PATCH {name}`), "보관"/"보관 해제"(`PATCH {archived}`).
- 오른쪽: 선택한 프로젝트의 키 표(`GET /keys?projectId=`): 이름, 접두사(`tt_Wm7KQ…`), 생성 시각, 마지막 사용(없으면 "사용 없음"), 상태 배지(활성/폐기). 폐기된 키는 흐리게.
- "키 발급" 버튼 → 모달: 이름 입력 → `POST /projects/{id}/keys` → 평문 키를 한 번 보여 주는 화면으로 바뀜. 복사 버튼(`navigator.clipboard.writeText`), "이 키는 다시 볼 수 없습니다" 안내, 닫기. 보관된 프로젝트에서는 버튼을 비활성화하고 이유를 보여 준다(서버도 409로 거절한다).
- "폐기" → 확인 대화상자("키 tt_Wm7KQ…를 폐기합니다. 이 키로 오는 요청은 즉시 401이 됩니다") → `DELETE /keys/{id}` → 목록 갱신.
- 오류(400·404·409)는 서버 메시지를 화면 상단 `Alert`로 보여 준다.

### 3. 요금표 화면 (`#/prices`)

- 표: `GET /prices`를 (공급처, 모델)로 묶어 가장 늦은 `effectiveFrom` 행을 보여 준다. 열: 공급처, 모델, 입력, 캐시 입력(없으면 "입력과 같음"), 출력, 적용 시작, 이력 수. "이력 n" 클릭으로 그 모델의 이전 행들을 펼친다. 단가는 `formatUsd`, 단위 표시 "USD / 100만 토큰".
- "단가 추가" 폼: 공급처(활성 공급처 목록에서 고르거나 직접 입력), 모델, 입력 단가, 출력 단가, 캐시 입력 단가(선택), 적용 시작 날짜(기본 오늘, `YYYY-MM-DD`). 제출은 `PUT /prices`. 201이면 "추가됨", 200이면 "교체됨"으로 알린다.
- 행의 "수정"은 폼에 그 행의 공급처·모델·적용 시작을 채운 채 단가만 바꾸게 한다(같은 키의 `PUT`은 단가 교체, 004 결정). 폼 아래에 "가격이 바뀐 날짜부터 적용하려면 적용 시작을 그 날짜로 바꿔 새 행으로 추가하세요"를 적는다.
- "삭제" → 확인 → `DELETE /prices/{id}`. 409면 "사용 기록이 참조하는 단가는 삭제할 수 없습니다. 더 늦은 적용 시작으로 새 행을 추가하세요"를 보여 준다.
- 숫자 입력 검증은 서버 규칙(음수 불가, 소수 8자리까지)을 클라이언트에서도 같은 메시지로 먼저 막는다.

### 4. 로그인과 인증

- 처음 열면 로그인 화면. 토큰 입력 후 제출 → `GET /admin/v1/providers`에 Bearer로 호출 → 200이면 토큰을 `sessionStorage`에 넣고 레이아웃으로 전환, 401이면 "관리자 토큰이 올바르지 않습니다", 네트워크 오류면 "서버에 연결할 수 없습니다(주소 확인)".
- 어느 화면에서든 응답이 401이면 토큰을 지우고 로그인 화면으로 돌아가며 "세션이 끊겼습니다. 다시 로그인하세요"를 띄운다.
- 로그아웃은 토큰을 지운다. 토큰은 `sessionStorage`에만 있어 탭을 닫으면 사라진다(계획 §4.4).
- 토큰은 화면·로그·URL에 찍지 않는다. 입력란은 `type="password"`.

### 5. 개발 서버와 빌드

- `vite.config.ts`: `server.proxy['/admin'] = 'http://localhost:8080'`. 개발 중 대시보드(5173)가 서버(8080)를 같은 출처처럼 부르므로 CORS 설정이 필요 없다. 011의 nginx가 운영에서 같은 역할을 한다.
- 같은 파일에 vitest 설정(`test.environment = 'jsdom'`, `setupFiles`, `include: src/**/*.test.{ts,tsx}`).
- `package.json` 스크립트: `test: vitest run`, `test:watch: vitest`. 기존 `build`·`lint`는 그대로.
- `tsconfig.app.json`에 테스트 타입(`vitest/globals`는 쓰지 않고 명시적 import) 외 변경 없음.

### 6. 새 개발 의존성 (npm 레지스트리에서 2026-11-07 확인)

| 패키지 | 버전 | 공개일 | 고른 이유 |
|---|---|---|---|
| `vitest` | 5.0.5 | 2026-10-15 | 5.0.0이 2026-09-03에 나와 두 달 지났고 패치 5개가 쌓였다. 5.1.0(2026-11-02)은 닷새밖에 안 되어 보류. peer `vite ^6 \|\| ^7 \|\| ^8`로 Vite 8.3.5와 맞음 |
| `jsdom` | 30.1.3 | 2026-10-15 | 31.0.0(2026-11-05)은 이틀밖에 안 되어 보류. engines에 Node 26 포함 |
| `@testing-library/react` | 16.3.3 | 2026-08-27 | peer React ^19. `@testing-library/dom`을 peer로 요구 |
| `@testing-library/dom` | 10.4.2 | | 위 peer |
| `@testing-library/jest-dom` | 7.0.1 | 2026-08-09 | `toBeInTheDocument` 같은 매처. `/vitest` 진입점으로 등록 |
| `@testing-library/user-event` | 14.6.8 | 2026-10-13 | 클릭·입력을 사용자처럼 |

런타임 의존성은 더하지 않는다. 라우팅은 40줄 안쪽의 해시 라우터, 데이터 요청은 `fetch`, 상태는 React 훅으로 충분하다(화면 넷, 폼 셋). React Router·TanStack Query·UI 라이브러리를 들이지 않는 이유는 의존성을 최소로 두라는 작업 규칙과, 이 규모에서는 배울 것보다 설정이 더 많기 때문이다. 010의 차트는 계획대로 Recharts 3.10.1(npm 레지스트리 최신, 2026-11-07 확인)을 그때 더한다.

### 7. 디자인

- 구현 시작 때 `frontend-design` 스킬을 읽고 그 지침으로 디자인 토큰과 레이아웃을 정한다. 시스템 글꼴, 밝은/어두운 모드, 데스크톱 중심(최소 폭 900px에서 깨지지 않게), 표 중심 화면이라 밀도는 높게.
- UI 문구는 한국어, 식별자는 영어(작업 규칙). 날짜는 브라우저 시간대로 보여 주고, 금액은 USD.
- 접근성: 모든 입력에 `label`, 모달은 포커스 이동과 Esc 닫기, 상태 배지는 색과 글자를 함께.

### 8. 테스트

`fetch`를 `vi.stubGlobal`로 바꾸는 `test/fakeApi.ts`에 경로표(`GET /admin/v1/projects` → 응답)를 등록해 컴포넌트가 실제 요청 흐름 그대로 돈다. 서버는 띄우지 않는다.

| 파일 | 내용 |
|---|---|
| `api/client.test.ts` (3) | Bearer 헤더 부착, `AdminError` → `ApiError(status, code, message)`, 401이면 `onUnauthorized` 호출 |
| `format.test.ts` (2) | `formatUsd`(1.00000000 → "1.00", 0.00000015 → "0.00000015"), `formatDateTime`·`formatRelative` |
| `pages/LoginPage.test.tsx` (3) | 제출 시 `GET /providers`에 입력 토큰이 실림, 401 → 오류 문구, 200 → 레이아웃 표시와 sessionStorage 저장 |
| `pages/keys/KeysPage.test.tsx` (4) | 프로젝트·키 목록 렌더, 새 프로젝트 생성 후 목록 갱신, 발급 모달이 평문 키를 한 번 보여 주고 복사 호출, 폐기는 확인 뒤 `DELETE`와 상태 변경 |
| `pages/prices/PricesPage.test.tsx` (3) | 모델별 최신 행과 이력 수, 추가 폼의 검증 메시지와 `PUT` 본문, 삭제 409 메시지 |
| `router/useHashRoute.test.ts` (1) | 해시 변경이 경로 상태로 반영 |

합계 16개 안팎. `npm run build`와 `npm run lint`도 통과해야 한다.

### 9. 수동 확인

Docker Desktop·compose PostgreSQL·`bootRun`(관리자 토큰 설정) 뒤 `npm run dev`를 띄우고 앱 내장 브라우저(또는 Playwright)로 다음을 확인한다: 틀린 토큰 → 오류, 맞는 토큰 → 키 관리 화면, 프로젝트 생성 → 키 발급(평문 1회, 복사) → 폐기 → 상태 변경, 요금표에서 `fake/fake-small` 이력 확인 → 새 단가 추가(201) → 같은 키로 교체(200) → 사용 기록이 참조하는 행 삭제 시 409 메시지. 화면을 캡처해 dev-log에 결과를 적는다.

### 10. 승인이 필요한 항목

1. 새 개발 의존성 여섯 개와 버전(6절). 최신 대신 두 달 된 5.0.5와 10월의 jsdom 30.1.3을 고른 것.
2. 런타임 의존성 없이 해시 라우터·`fetch`·React 훅으로 구성(6절). React Router 등은 쓰지 않는다.
3. UI 문구 한국어, UI 라이브러리 없이 `frontend-design` 지침으로 직접 CSS, 템플릿 자산 삭제(7절).
4. 토큰은 `sessionStorage`, 401이면 자동 로그아웃(4절).
5. 요금표의 "수정"은 같은 (공급처, 모델, 적용 시작)의 단가 교체이고, 가격 변경은 새 행 추가로 안내(3절, 004 결정 유지).
6. Vite 개발 프록시 `/admin` → `localhost:8080`(5절).

## 확인한 사실 (2026-11-07)

- 관리 API 경로와 응답은 서버 코드에서 확인했다: `GET/POST /admin/v1/projects`, `GET /projects/{id}`, `PATCH /projects/{id}`(`name`, `archived`), `POST /projects/{id}/keys`, `GET /keys?projectId`, `DELETE /keys/{id}`, `GET /prices?provider&model`, `PUT /prices`, `DELETE /prices/{id}`, `GET /providers`. 오류 본문 `{"error": {"code", "message"}}`.
- 대시보드 현재 상태: React 19.3.1, Vite 8.3.5, TypeScript 7.0.2(`~7.0.2`), oxlint 1.89.0, `@vitejs/plugin-react` 6.1.2(peer `vite ^8`). Node 26.13.0, npm 11.20.0.
- 6절의 버전·공개일은 `npm view <pkg> version|time|peerDependencies`로 npm 레지스트리에서 읽었다.

## 디자인 계획 (`frontend-design` 지침으로 정함)

대상은 "요금소 창구"다. 운영자가 하루에 몇 번 들러 키를 끊고 단가를 고치는 도구이므로, 보여 주는 것보다 읽고 누르는 것이 먼저다. 표가 화면의 중심이고 밀도는 높게, 장식은 정보가 있을 때만 둔다.

- 색: `--bg #f4f6f4`(옅은 회녹 바탕), `--surface #fff`, `--ink #1b2430`, 강조 `--accent #0b6e99`(도로 표지판 파랑), 상태색 `--ok #1f7a4d`·`--danger #b63a28`·`--warn #8d6410`. 어두운 모드는 `prefers-color-scheme`로 같은 토큰을 덮어쓴다. 크림 바탕 + 세리프 제목, 카드마다 그림자, 전부 대문자 라벨처럼 어느 화면에나 나오는 기본값은 피했다.
- 글꼴: 시스템 한국어 스택(Pretendard → Apple SD Gothic Neo → Malgun Gothic → Noto Sans KR → system-ui) 14px 하나. 숫자는 `font-variant-numeric: tabular-nums`로 표에서 자릿수를 맞추고, 고정폭 글꼴은 키 접두사와 평문 키에만 쓴다.
- 레이아웃: 왼쪽 208px 레일(이름, 탭, 로그아웃) + 본문. 키 관리는 340px 프로젝트 열 + 키 표, 요금표는 폼 패널 + 표 패널. 900px 아래에서는 한 열로 쌓이고 표는 패널 안에서 가로로 스크롤한다.
- 힘을 주는 곳은 하나: 발급 직후의 평문 키 티켓(점선 강조 테두리, 기울어진 "한 번만 표시" 도장). 나머지는 1px 선과 여백으로만 나눈다. 움직임은 모달이 뜰 때뿐이고 `prefers-reduced-motion`을 따른다.
- 접근성: 모든 입력에 `label`(`Field`가 `useId`로 묶음), 모달은 열릴 때 첫 입력으로 포커스 이동과 Esc 닫기, 상태 배지는 색과 글자를 함께, 프로젝트 선택과 표의 동작은 모두 `button`.

## 결정과 이유

- `auth/AuthContext.tsx` 하나로 명세했던 파일을 `AuthContext.ts`(컨텍스트, 타입, `useAuth`)와 `AuthProvider.tsx`(컴포넌트)로 나눴다. oxlint의 `react(only-export-components)`(Fast Refresh) 경고를 없애기 위해서다. 토큰은 `TokenStore`(sessionStorage, 막혀 있으면 메모리)에 두고 `fetch` 래퍼가 호출 때마다 읽는다. 렌더 중에 ref를 읽는 경고를 피하면서, 로그인 직후의 첫 호출에도 새 토큰이 실리게 하려는 것이다.
- 데이터 로딩은 `useEffect` 안에서 Promise 체인 + `cancelled` 플래그로 하고, 다시 읽기는 `useReducer` 카운터(`version`)를 올려 effect를 재실행한다. React Compiler 계열 lint 규칙 `set-state-in-effect`가 effect 안의 동기 `setState`를 막기도 하고, 프로젝트를 빠르게 바꿀 때 늦게 온 응답이 상태를 덮어쓰지 않게 하기 위해서다. 키 목록은 `{projectId, keys}`로 저장해 "선택한 프로젝트와 다르면 아직 로딩 중"을 렌더에서 파생하므로 로딩 상태를 따로 `setState`하지 않는다.
- 프로젝트 행은 `<li onClick>`이 아니라 행을 채우는 `<button class="project-select">`다. 키보드로도 선택할 수 있어야 해서다. 이름 변경은 연필 아이콘 대신 "이름 변경" 글자 버튼으로 했다. 아이콘 라이브러리를 들이지 않고, 글자가 더 분명하다.
- 요금표 모델 파일은 `priceModel.ts`다. 처음 이름 `priceForm.ts`가 Windows·macOS의 대소문자 무시 파일시스템에서 `PriceForm.tsx`와 충돌했다(겪은 문제 1).
- 복사 버튼은 실패하면 평문 키를 선택 상태로 만들어 Ctrl+C로 복사할 수 있게 했다. 앱 내장 브라우저에서 `navigator.clipboard.writeText`가 거부되는 것을 보고 더했다.
- 로그아웃 버튼은 레일 맨 아래에 뒀다(명세의 "위쪽"에서 바꿈). 레일이 세로 구조라 아래가 자연스럽고, 자주 누르는 버튼이 아니다.
- 데이터 흐름: 화면은 `createAdminApi(client)`의 함수만 부르고 URL을 만들지 않는다. 테스트는 같은 `fetch` 경로를 `FakeAdminApi`(경로표 + 호출 기록)로 흉내 낸다.

## 겪은 문제

1. `tsc -b`가 TS1149/TS1261("casing만 다른 파일")로 실패했다. `priceForm.ts`와 `PriceForm.tsx`를 같은 폴더에 둔 탓이다. `priceModel.ts`로 이름을 바꿨다.
2. oxlint React 규칙 경고 9개(`set-state-in-effect` 4, `refs` 3, `only-export-components` 2). effect 안의 동기 `setState`를 없애고(Promise 체인 + 버전 카운터, 로딩 상태 파생), ref 대신 `TokenStore` 객체를 쓰고, `useAuth`와 `groupKey`를 컴포넌트 파일 밖으로 옮겨 0개로 만들었다.
3. 앱 내장 브라우저의 `type` 동작이 `inputMode="decimal"` 입력란(단가)에는 글자를 넣지 못했다(공급처·모델 입력란은 정상). 원인은 확인하지 못했고, 값을 직접 넣는 `form_input`으로는 정상 동작했다. 실제 키보드 입력은 사용자가 한 번 확인해 주면 좋겠다(남은 문제).
4. 같은 브라우저에서 복사 버튼이 "복사 실패"를 보였다(콘솔 오류 없음, clipboard 쓰기 거부). 위의 선택 대체 동작을 더했다.
5. 단가 폼의 `align-items: end` 때문에 힌트가 있는 필드의 라벨이 다른 필드보다 위로 밀렸다. `start`로 바꿨다.
6. 프로젝트 열(300px)에서 "2026. 11. 7. 오전 2:12 생성"과 "보관 해제" 버튼이 두 줄로 접혔다. 날짜만 보여 주는 `formatDate`로 바꾸고 열을 340px로 넓혔다.
7. 375px 폭에서 키 표의 "마지막 사용" 열이 세로로 쪼개졌다. 900px 아래에서는 표에 `min-width: 640px`를 주고 패널 안에서 가로 스크롤하게 했다.
8. Docker Desktop이 세션 시작 때 꺼져 있었다(어제와 같음). 샌드박스 밖에서 띄운 뒤 `docker compose up -d postgres`.

## 실행한 검증과 결과 (2026-11-07)

- `npm run lint`: oxlint 1.89.0, 경고 0.
- `npm run build`: `tsc -b` 통과, `vite build` 산출물 `index.html` 0.46 kB, CSS 9.00 kB(gzip 2.61 kB), JS 247.27 kB(gzip 77.04 kB).
- `npm test`: vitest 5.0.5, 6개 파일 16개 테스트 통과(2.33초, jsdom). `api/client.test.ts` 3, `format.test.ts` 2, `pages/LoginPage.test.tsx` 3, `pages/keys/KeysPage.test.tsx` 4, `pages/prices/PricesPage.test.tsx` 3, `router/useHashRoute.test.ts` 1.
- 코드 규모: `dashboard/src` 소스 2,326줄(ts·tsx·css, 테스트 제외), 테스트와 도우미 456줄.
- 수동 확인 환경: Docker Desktop 29.9.2의 compose PostgreSQL, 008 수동 확인에 쓴 `server/build/libs/server-0.1.0-SNAPSHOT.jar`(`TOLLGATE_ADMIN_TOKEN=manual-check-token-0123456789`, `TOLLGATE_PRICES_FILE=config/prices.example.yaml`), `npm run dev`(5173, `/admin` 프록시), 앱 내장 브라우저 1280×820. 서버 로그에 ERROR 없음(WARN은 openai·anthropic 키 없음 2건).

| 단계 | 결과 |
|---|---|
| 틀린 토큰 입력 | "관리자 토큰이 올바르지 않습니다." 표시, `sessionStorage` 비어 있음 |
| 맞는 토큰 입력 | 키 관리 화면으로 전환, 기존 프로젝트 3개와 `manual-006`의 키 1개(`tt_Wm7KQ…`, 활성, 마지막 사용 3일 전) 표시 |
| 프로젝트 생성 `manual-009` | 목록에 추가되고 바로 선택됨, 성공 알림 |
| 키 발급 `claude-code` | 모달이 티켓으로 바뀌어 평문 키를 한 번 표시("한 번만 표시" 도장). 복사 버튼은 이 브라우저에서 실패 문구(겪은 문제 4). 닫은 뒤 목록에 활성 키로 나타남 |
| 키 폐기 | 확인 대화상자(Tab·Enter로도 동작) → 행이 흐려지고 배지 "폐기", 폐기 버튼 사라짐, 알림 |
| 보관 / 보관 해제 | 보관하면 목록에서 빠지고 첫 프로젝트가 선택됨. "보관된 프로젝트 보기"를 켜면 "보관됨" 배지와 함께 나타나고, 선택하면 "키 발급" 버튼이 비활성(이유 툴팁). 해제하면 원래대로 |
| 요금표 표시 | 11개 모델 11행(`prices.example.yaml` + 004·007 수동 확인 행), 캐시 단가 없는 행은 "입력과 같음" |
| 폼 검증 | 출력 단가 `abc` → 빨간 테두리와 "0 이상의 숫자를 소수 8자리까지 입력하세요.", 요청 없음 |
| 단가 추가 (`fake/fake-manual-009`, 1.5/3, 2026-11-07) | 201 → "단가를 추가했습니다 (…)" 알림, 표 12행 |
| 수정 → 교체 (입력 1.5 → 2) | 폼에 행이 채워지고 저장하면 200 → "단가를 교체했습니다" 알림 |
| 이력 (같은 모델, 2026-12-01, 4/8) | 201 → 최신 행이 12-01로 바뀌고 "이력 1" 버튼, 누르면 11-07 행이 "이전"으로 펼쳐짐 |
| 삭제 (11-07 행, 12-01 행) | 204 → 알림과 표 갱신(확인 뒤 11행으로 복귀) |
| 참조 중인 단가 삭제 (`anthropic/claude-haiku-5-5`) | 409 → "사용 기록이 참조하는 단가는 삭제할 수 없습니다. 더 늦은 적용 시작으로 새 행을 추가하세요." 행은 그대로 |
| 세션 끊김 | `sessionStorage`의 토큰을 틀린 값으로 바꾸고 새로 고침 → 첫 호출 401 → 로그인 화면 + "세션이 끊겼습니다. 다시 로그인하세요." |
| 로그아웃 | 로그인 화면으로, 알림 없음 |
| 어두운/밝은 모드, 375px | 두 모드 모두 토큰이 적용됨. 좁은 폭에서 레일이 위로 가고 표는 가로 스크롤 |

화면 캡처는 저장소에 두지 않는다. 확인 중 만든 `manual-009` 프로젝트와 폐기된 키는 로컬 DB에 남겨 두었고, `fake-manual-009` 단가 행 둘은 지웠다.

## 남은 문제

- 복사 버튼을 일반 브라우저(Chrome 등)에서 확인하지 못했다. 앱 내장 브라우저에서는 clipboard 쓰기가 거부되어 대체 동작(키 선택)만 확인했다.
- 단가 입력란에 실제 키보드로 입력하는 것을 확인하지 못했다(겪은 문제 3). 사용자 확인이 필요하다.
- 세션 끊김 알림은 다음 로그인 성공 때까지 남아, 틀린 토큰 오류와 함께 보일 수 있다. 사소하지만 문구가 둘이 된다.
- `main.tsx`의 `StrictMode` 때문에 개발 모드에서는 effect가 두 번 실행되어 첫 조회 요청이 두 번 나간다(`cancelled` 플래그로 결과는 한 번만 반영). 운영 빌드에는 없다.
- `.claude/launch.json`(앱 내장 브라우저용 `npm run dev` 실행 설정)을 저장소에 둘지는 정하지 않았다.
- 010에서 `#/overview`·`#/requests`를 더할 때 `Route` 타입과 `Shell`의 탭만 넓히면 된다.
