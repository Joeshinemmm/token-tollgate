# 008 사용량 조회 API

- 명세 작성: 2026-11-05, 승인: 2026-11-05 (6절의 다섯 항목 모두), 구현·검증: 2026-11-06
- 선행: 006·007 중계가 `usage_records`를 채운다. compose DB에는 수동 확인과 실제 Anthropic 시험으로 남긴 기록이 있다
- 후속: 009 대시보드(키 관리·요금표)와 010 대시보드(개요·요청 목록)가 이 API를 그대로 쓴다

## 목표

`usage_records`를 대시보드가 필요한 두 모양으로 조회하는 관리 API를 만든다. 하나는 기간 안의 기록을 날짜·프로젝트·모델·태그로 묶어 요청 수·토큰·비용을 더한 집계(`summary`), 다른 하나는 최근 요청을 필터와 커서로 넘겨 보는 목록(`records`)이다. 두 프로토콜의 기록을 구분 없이 함께 다룬다. 저장은 UTC지만 날짜 묶음은 사용자의 시간대(`tz`)로 계산한다(계획 §4.6).

## 완료 기준

1. 알려진 기록을 넣고 네 가지 묶음(`day`, `project`, `model`, `tag`)의 요청 수·토큰·비용이 손으로 계산한 값과 일치한다.
2. 같은 기록이 `tz`에 따라 다른 날짜 묶음에 들어간다(UTC 저녁은 서울의 다음 날).
3. `records`의 필터와 커서가 겹침·누락 없이 전체를 훑는다.
4. 잘못된 파라미터는 400과 관리 API 오류 형식으로 거절되고, 관리자 토큰 없이는 401이다.
5. 테스트는 Testcontainers만 쓰고, 수동 확인은 compose DB에 이미 있는 실제 기록(006·007 수동 확인, 실제 Anthropic 2건)으로 집계를 본다.

## 명세

### 1. `GET /admin/v1/usage/summary`

| 파라미터 | 뜻 | 기본값 |
|---|---|---|
| `from` | 시작 시각(포함). ISO-8601 instant(`2026-11-04T15:00:00Z`) 또는 날짜(`2026-11-05`, `tz`의 자정) | `to`에서 7일 전 |
| `to` | 끝 시각(제외). 같은 형식 | 지금 |
| `groupBy` | `day`, `project`, `model`, `tag` | `day` |
| `tz` | IANA 시간대. `day` 묶음과 날짜형 `from`·`to` 해석에 쓴다 | `UTC` |
| `projectId` | 프로젝트 필터 | 없음 |
| `tag` | 태그 필터(정확 일치) | 없음 |
| `model` | 모델 필터. `model_served`가 있으면 그것, 없으면 `model_requested`와 비교 | 없음 |
| `provider` | 공급처 필터 | 없음 |

응답(수동 확인 1번의 일부):

```json
{
  "from": "2026-10-31T15:00:00Z", "to": "2026-11-06T15:00:00Z", "tz": "Asia/Seoul", "groupBy": "model",
  "totals": { "requests": 15, "okRequests": 9, "inputTokens": 880, "cachedInputTokens": 140, "outputTokens": 59,
              "cost": 0.00082400, "priceMissingRequests": 0, "usageUnknownRequests": 6 },
  "groups": [
    { "key": "claude-haiku-5-5", "label": null, "provider": "anthropic", "requests": 3, "okRequests": 2, "inputTokens": 40,
      "cachedInputTokens": 0, "outputTokens": 8, "cost": 0.00000800, "priceMissingRequests": 0, "usageUnknownRequests": 1 }
  ]
}
```

| 지표 | 정의 |
|---|---|
| `requests` | 기간 안 기록 수(실패 포함) |
| `okRequests` | `outcome = ok` |
| `inputTokens`, `cachedInputTokens`, `outputTokens` | 각 컬럼의 합 |
| `cost` | `cost`의 합. null은 더하지 않는다. 더할 것이 없으면 `0`. 소수 8자리 숫자(문자열이 아님) |
| `priceMissingRequests` | `cost IS NULL AND usage_source <> 'none'`: 사용량은 알지만 단가가 없는 요청 |
| `usageUnknownRequests` | `usage_source = 'none'`: 사용량 자체를 모르는 요청(오류·끊김) |

묶음 키:

| `groupBy` | `key` | `label` | `provider` | 정렬 |
|---|---|---|---|---|
| `day` | `tz` 기준 `yyyy-MM-dd` | null | null | 날짜 오름차순. 기록 없는 날은 나오지 않는다(대시보드가 0으로 채움) |
| `project` | 프로젝트 id | 프로젝트 이름 | null | 비용 내림차순, 같으면 요청 수 내림차순 |
| `model` | `COALESCE(model_served, model_requested)` | null | 공급처 이름 | 같음 |
| `tag` | 태그, 없으면 null | null | null | 같음 |

검증: `from < to`, 범위 366일 이하, `tz`는 `ZoneId.of`로 해석 가능, `groupBy`는 네 값 중 하나. 위반은 400 `{"error": {"code": "bad_request", "message": "..."}}`(003의 `AdminErrorHandler`가 `BadRequestException`을 받는다).

### 2. `GET /admin/v1/usage/records`

| 파라미터 | 뜻 | 기본값 |
|---|---|---|
| `from`, `to`, `tz` | 1절과 같음 | 같음 |
| `projectId`, `tag`, `model`, `provider` | 1절과 같은 필터 | 없음 |
| `outcome` | `ok`, `upstream_error`, `client_abort`, `timeout`, `gateway_error` | 없음 |
| `limit` | 1~200 | 50 |
| `before` | 커서. 이 id보다 작은 기록만 | 없음(최신부터) |

응답 한 건의 모양(수동 확인 6번, 실제 Anthropic 스트리밍 요청):

```json
{ "records": [
    { "id": 15, "requestId": "…", "startedAt": "2026-11-04T16:45:31.546364Z", "projectId": "…", "projectName": "real-anthropic-test",
      "keyId": "…", "keyPrefix": "tt_RRghT", "tag": null, "protocol": "anthropic", "provider": "anthropic",
      "modelRequested": "claude-haiku-5-5", "modelServed": "claude-haiku-5-5", "inputTokens": 20, "cachedInputTokens": 0, "outputTokens": 4,
      "cost": 0.00000400, "usageSource": "stream", "ttftMs": 845, "durationMs": 846, "httpStatus": 200, "streamed": true,
      "outcome": "ok", "errorType": null } ],
  "nextBefore": 13 }
```

- 정렬은 `id` 내림차순이다. `id`는 bigserial이라 저장 순서이고, 기록은 요청이 끝날 때 저장되므로 "최근에 끝난 요청"순이다. `started_at` 대신 `id`를 커서로 쓰는 이유는 동률이 없어 페이지 경계에서 겹침·누락이 생기지 않기 때문이다.
- `nextBefore`는 돌려준 기록이 `limit`개일 때 마지막 기록의 `id`, 그보다 적으면 null(끝).
- `projectName`과 `keyPrefix`는 `projects`·`virtual_keys`를 LEFT JOIN해 채운다. 키 평문이나 해시는 내보내지 않는다.

### 3. 구현

| 파일 | 역할 |
|---|---|
| `usage/UsageQuery.kt` | 파라미터 해석과 검증: `UsageWindow(from, to, zone)`, `UsageFilter`, `SummaryQuery`, `RecordsQuery`, `GroupBy`. 날짜형 `from`·`to`는 `tz`의 자정으로, 기본값과 상한 적용 |
| `usage/UsageQueries.kt` | `DatabaseClient`로 SQL 실행. WHERE 절은 조건이 있을 때만 붙이고 값은 전부 바인드한다. `groupBy`별 SELECT 조각은 고정 네 가지(`to_char(r.started_at AT TIME ZONE :tz, 'YYYY-MM-DD')`, `r.project_id::text` + `p.name`, `COALESCE(r.model_served, r.model_requested)` + `r.provider`, `r.tag`)이고 `GROUP BY 1, 2, 3`. `tz`도 바인드 값 |
| `usage/UsageQueryService.kt` | 묶음 정렬과 합계(합계는 묶음의 합) |
| `admin/UsageController.kt`, `admin/UsageDtos.kt` | 두 엔드포인트와 응답 DTO |
| `README.md` | "사용량 조회" 절 |
| `docs/dev-log/000-phase1-plan.md` §8 | 변경 이력 |

- 인덱스는 추가하지 않는다. 기존 `(project_id, started_at DESC)`·`(started_at DESC)`가 기간·프로젝트 필터를, PK가 `before` 커서를 받는다. 1단계 규모에서 집계는 범위 스캔으로 충분하고, 느려지면 3단계에서 일별 집계 테이블을 검토한다.
- `DatabaseClient`를 쓰는 이유: 필터가 있고 없고에 따라 SQL이 달라지는데, Spring Data의 `@Query`는 null 파라미터를 `IS NULL OR` 꼴로 다루려면 R2DBC PostgreSQL이 바인드 타입을 못 정하는 경우가 있다. SQL 문자열에 들어가는 것은 고정 조각뿐이고 사용자 값은 바인드로만 들어간다.
- 합계는 별도 쿼리 대신 묶음의 합으로 계산한다. 묶음은 기간 안의 행을 빈틈없이 나누므로 같은 값이고 쿼리가 하나 준다.
- 금액은 `BigDecimal`로 읽어 소수 8자리 평문으로 직렬화한다(004의 설정 그대로).
- 새 의존성: 없음(`DatabaseClient`는 `spring-boot-starter-data-r2dbc`에 있다).

### 4. 테스트

| 파일 | 내용 |
|---|---|
| `admin/UsageApiTest` (통합, 10) | 전용 프로젝트 2개·키 2개를 만들고 `UsageRecordRepository`로 2025-03-01의 기록 8건을 직접 넣는다(다른 테스트가 쓰는 현재 시각과 겹치지 않게). 시각은 14:30Z(서울 03-01 23:30)와 15:30Z(서울 03-02 00:30)로 날짜 경계를 걸치고, 모델 2종(fake-small, fake-large)·공급처 2종, 태그 2종과 null, 비용 있는 것 6건·단가 없는 것 1건·오류(usage none) 1건. 1 `day`: UTC 하루(8건, 1180/160/118 토큰, 0.00742000) ↔ Asia/Seoul 이틀(3건 0.00035000 + 5건 0.00707000), 합계 동일. 2 `project` 묶음의 label과 지표(A 0.00730000, B 0.00012000 + priceMissing 1 + usageUnknown 1), 비용 내림차순. 3 `model` 묶음(fake-large, fake-small, gpt-x)과 provider. 4 `tag` 묶음에 null 키 포함, 비용 내림차순. 5 필터 `projectId`+`tag`, `model`, `provider`, 기록 없는 기간은 빈 묶음과 0. 6 `records` 정렬·필드(projectName, keyPrefix, startedAt, cost 숫자, null 태그·비용)와 `outcome` 필터. 7 `limit=3` 커서로 3페이지에 8건이 한 번씩. 8 400 여덟 경우의 메시지와 `limit=abc`. 9 토큰 없이 401. 10 기본값(7일, UTC, day) |
| `usage/UsageQueryTest` (단위, 4) | instant·날짜형 해석과 tz 자정·오프셋 표기, 기본 7일, 상한·잘못된 값 거절, 필터·limit 해석 |

### 5. 수동 확인

compose PostgreSQL + 서버 jar(가짜 공급처 불필요): compose DB의 실제 기록으로 열 가지 호출. 결과는 "실행한 검증과 결과"에 있다.

### 6. 승인이 필요한 항목 (2026-11-05 모두 승인됨)

1. `from`·`to` 의미: 포함/제외, 날짜형은 `tz`의 자정, 기본 최근 7일, 최대 366일.
2. `records` 커서는 `id` 내림차순(`before`). 계획은 파라미터 이름만 정했고 기준은 여기서 정한다.
3. 집계 지표 정의, 특히 `cost`는 null을 빼고 더하며 `priceMissingRequests`·`usageUnknownRequests`를 함께 돌려준다.
4. `model` 묶음 키 = 공급처 + `COALESCE(model_served, model_requested)`, 그리고 계획에 없던 `provider` 필터와 `records`의 `outcome` 필터 추가.
5. `DatabaseClient`로 동적 SQL(값은 바인드만). 새 의존성 없음.

## 확인한 사실

- 외부 API는 없다. PostgreSQL의 `AT TIME ZONE`에 시간대 이름을 바인드 값으로 주어 `to_char(... AT TIME ZONE $1, 'YYYY-MM-DD')`가 R2DBC에서 동작함을 통합 테스트 1과 수동 확인 2·3으로 확인했다.
- 집계 결과의 `cost`는 `numeric` 합이라 `BigDecimal`로 읽히고, 응답에서는 `0.00082400`처럼 소수 8자리 숫자로 나간다(004의 `write-bigdecimal-as-plain`).
- `ZoneOffset.UTC`의 `id`는 `"Z"`다. 응답의 `tz`에 `UTC`를 내려 주기 위해 기본 시간대는 `ZoneId.of("UTC")`로 둔다(겪은 문제).

## 결정과 이유

- 합계를 묶음의 합으로 구한다. 쿼리가 하나로 끝나고, 합계와 묶음이 어긋날 수 없다.
- 묶음 정렬은 DB가 아니라 코드에서 한다. 묶음 수는 기간 안의 날짜·프로젝트·모델·태그 수로 한정되어 작고, 네 가지 정렬 규칙을 SQL 넷에 반복해 적는 것보다 한 곳이 낫다.
- `cost`를 숫자로 보낸다(명세의 예시는 문자열처럼 적었으나 004의 요금표 API와 같은 숫자 형식을 따른다). 소수 8자리 평문이라 JavaScript에서도 정밀도를 잃지 않는 범위다.
- 집계 결과의 `key`가 실패한 요청에서는 `model_requested`로 떨어진다. 가짜 공급처는 지시가 붙은 이름(`fake-small:err=429`)이 그대로 키가 되어 묶음이 갈라지는데, 실제 공급처의 요청 이름은 평범한 모델명이라 문제가 없다. 남은 문제에 적었다.

## 겪은 문제

- 응답의 `tz`가 `UTC`가 아니라 `Z`로 나왔다. `ZoneOffset.UTC.getId()`가 `"Z"`를 돌려주기 때문이다. 기본값을 `ZoneId.of("UTC")`로 바꿨다. 그 외에는 첫 실행에서 126개가 통과했다.
- Docker Desktop이 다시 내려가 있었다(날이 바뀜). 구현 시작 때 샌드박스 밖에서 띄우고 compose PostgreSQL을 다시 올렸다.

## 실행한 검증과 결과

| 검증 | 명령 | 결과 |
|---|---|---|
| 서버 테스트 | `./gradlew :server:test` | 128개 통과, 실패 0 (008에서 14개 추가), 19초 |
| 기동 | 서버 jar + compose PostgreSQL | 2초 |
| 1 `model`, Asia/Seoul, 11-01~11-07 | 실제 기록 15건 | 합계 15건(성공 9), 토큰 880/140/59, 비용 0.00082400, usageUnknown 6. `claude-haiku-5-5`(anthropic) 3건 중 성공 2건 0.00000800 = 실제 키 시험 2건, 나머지 1건은 키 없이 보낸 503 |
| 2·3 `day` UTC ↔ Asia/Seoul | 같은 기간 | UTC는 11-02(7건)·11-04(8건), 서울은 11-03(7건)·11-05(8건). 합계 동일 |
| 4 `project` | | manual-006 7건 0.00046200, manual-007 6건 0.00035400, real-anthropic-test 2건 0.00000800, 비용 내림차순, label에 이름 |
| 5 `tag` + `provider=anthropic` | | 태그 없는 묶음 하나, 3건 |
| 6·7 `records limit=3` + `before=13` | | 15, 14, 13 → nextBefore 13 → 12, 11, 10 → nextBefore 10. projectName·keyPrefix·startedAt 포함, 평문 키 없음 |
| 8 `outcome=upstream_error` | | 4건(끊김 2, 529, 429), 비용 null, usageSource none, nextBefore null |
| 9 `tz=Mars/Olympus` | | 400 `bad_request` "tz must be a valid time zone id such as Asia/Seoul" |
| 10 기본 기간 | | `from`이 `to`의 정확히 7일 전, UTC, day |

전체 테스트 재실행 `./gradlew test --rerun-tasks`: BUILD SUCCESSFUL 37초, server 128개 + fake-provider 40개 = 168개 통과, 실패 0.

측정 조건: Windows 11 Home, JDK Temurin 25.0.5+8, Gradle 9.8.2, Docker Desktop 엔진 29.9.2, PostgreSQL 18(compose와 Testcontainers), 2026-11-06.

## 남은 문제

- 실패한 요청의 `model` 묶음 키가 `model_requested`로 떨어져, 가짜 공급처의 지시가 붙은 이름이 따로 묶인다. 대시보드(010)에서 가짜 공급처 기록을 보기 불편하면 지시 부분을 떼는 표시 규칙을 둘지 그때 정한다.
- 기록 없는 날은 `day` 묶음에 나오지 않는다. 010의 차트가 기간 안의 날짜를 채우고 0을 넣는다.
- 집계는 매번 범위를 훑는다. 기록이 수십만 건이 되면 느려지므로 3단계에서 일별 집계 테이블이나 물질화 뷰를 검토한다.
- `records`는 `tag`·`model` 정확 일치만 지원한다. 부분 일치 검색은 대시보드 요구가 생기면 더한다.
