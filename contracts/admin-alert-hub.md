# 관리자 알림 허브 계약 (member)

관리자 화면의 알림창(관리자용 · 개발자용 탭)이다. member 가 두 경로로 사건을 받아 `shop.admin_alerts` 에 저장하고, 화면은 아래 API 와 실시간 연결로 읽는다. 받는 경로는 `admin-alert-events.md`(preorder · order 의 이벤트)와 `admin-alert-grafana.md`(Grafana 경보)다. 받는 사람 · 심각도 · 묶음 · 재알림 · 해소는 member 가 정하고, 화면 문구와 갈 곳은 프론트가 `type` · `target` · `metrics` 로 만든다. 문서와 어긋나는 쪽이 고친다.

## type 규칙

| type | 받는 사람 | 심각도 | 대상 | 해소 | 주 숫자 | 받는 경로 |
| --- | --- | --- | --- | --- | --- | --- |
| `REGISTER_FAILED` | ADMIN | URGENT | NONE | Grafana resolved | `deadLetters` | Grafana |
| `EVENT_DLQ_WAITING` | DEVELOPER | CHECK | NONE | Grafana resolved | `waiting` | Grafana |
| `QUEUE_BRAKING` | DEVELOPER | URGENT | NONE | Grafana resolved | — | Grafana |
| `QUEUE_SERVER_UNHEALTHY` | ADMIN | CHECK → 5분 뒤 URGENT | NONE | Grafana resolved(waitingroom 이 통째로 내려가 지표가 5분 넘게 없을 때도 울린다) | — | Grafana |
| `EXTERNAL_SYSTEM_DOWN` | ADMIN | URGENT | NONE | Grafana resolved | — | Grafana |
| `SERVICE_INCIDENT` | ADMIN + DEVELOPER | URGENT | SERVICE | Grafana resolved | — | Grafana |
| `SERVICE_ERROR_SPIKE` | DEVELOPER | URGENT | SERVICE | Grafana resolved | — | Grafana |
| `SHIPPING_BACKLOG` | ADMIN | INFO | NONE | Grafana resolved | `total` | Grafana |
| `PREORDER_OPENED` | ADMIN | INFO | PRODUCT | 한 번 소식 | — | 이벤트(preorder) |
| `PREORDER_CLOSED` | ADMIN | INFO | PRODUCT | 한 번 소식 | — | 이벤트(preorder) |
| `DRAW_SETTLED` | ADMIN | INFO | DRAW | 한 번 소식 | — | 이벤트(order) |
| `DRAW_DELAYED` | ADMIN | CHECK | DRAW | 같은 회차의 `DRAW_SETTLED` | — | 이벤트(order) |
| `ADMISSION_STOPPED` | ADMIN + DEVELOPER | URGENT | PRODUCT | Grafana resolved | `waiting` | Grafana(나중) |
| `QUEUE_LONG_WAIT` | ADMIN | CHECK | PRODUCT | Grafana resolved | — | Grafana(나중) |
| `PAYMENT_FAILURE_SPIKE` | DEVELOPER | CHECK | NONE | Grafana resolved | — | Grafana(나중) |
| `RECONCILE_MISMATCH` | ADMIN + DEVELOPER | CHECK | NONE | 대조 결과 | `count` | 나중 |

- **받는 사람이 둘이면** 같은 사건을 두 건으로 저장한다. `metrics` 는 같고, 무엇을 보일지는 프론트가 `audience` 로 고른다.
- **모르는 type** 도 저장한다(개발자 · 확인). Grafana 에서 오면 열어 두고, 이벤트로 오면 한 번 소식으로 둔다.
- **한 번 소식**은 넣을 때 `RESOLVED` 이고 `resolvedAt` 은 발생 시각, 응답의 `resolvable` 은 `false` 다. 프론트는 해소 문구를 보이지 않는다.

## 묶음 · 재알림 · 해소

- **묶음.** 같은 사건(type · 받는 사람 · 대상 · 사건 이름)은 한 건이다. 사건 이름은 Grafana 경보의 `startsAt`, 이벤트의 회차 id(`campaignId` · 드로우 회차 id)다. 해소되기 전까지 숫자(`metrics`)와 `lastUpdatedAt` 만 갱신한다.
- **새 알림으로 올림.** `raisedAt` 을 지금으로 바꾸고 `readAt` 을 `null` 로 되돌린다(안 읽음). 목록 맨 위로 온다. 생길 때 · 다시 올릴 때 · 해소할 때 올린다.
- **재알림.** 주 숫자가 있는 type 만, 그 숫자가 마지막으로 올린 값보다 늘었을 때만 본다.
  - 2배 이상이거나 100 단위를 넘으면 바로 올린다(예: 10 → 20, 150 → 200).
  - 아니면 마지막으로 올린 뒤 5분이 지났을 때 올린다.
  - 숫자가 그대로거나 줄면 5분이 지나도 올리지 않는다. 주 숫자가 없는 type 은 다시 올리지 않는다 — ADMIN 의 `SERVICE_INCIDENT` 는 시작 · 해소 두 번만 온다.
- **승격.** `QUEUE_SERVER_UNHEALTHY` 는 처음 생긴 뒤 5분이 지난 갱신에서 `URGENT` 로 바꾸고 한 번 올린다. 갱신이 약 2분마다 오므로 실제로는 5~7분 사이에 바뀐다.
- **해소.** Grafana resolved 또는 같은 회차의 `DRAW_SETTLED` 가 오면 `RESOLVED` 로 바꾸고 `resolvedAt` 을 남긴 뒤 올린다. 흔들림 방지는 Grafana 규칙의 `for` · `keep_firing_for` 가 한다.

## API

권한은 ADMIN 이다(관리자 토큰, `Authorization: Bearer`). 토큰이 없으면 401, 회원 토큰은 403. 응답은 공통 봉투(`ApiResponse`)다. ALB 는 `/api/v1/admin/notifications*` 를 member 로 보낸다.

| 요청 | 응답 |
| --- | --- |
| `GET /api/v1/admin/notifications?audience=&status=&severity=&unreadOnly=&cursor=&size=` | 목록. `raisedAt` · `notificationId` 내림차순 커서. 조건을 비우면 거르지 않는다. `severity` 는 쉼표 · 반복(`URGENT,CHECK`). `size` 1~50(기본 20). `nextCursor` 가 `null` 이면 마지막 쪽 |
| `GET /api/v1/admin/notifications/unread-count` | `{"ADMIN": {"urgent": 1, "check": 3, "info": 5}, "DEVELOPER": {…}}` — 둘 다 늘 있다 |
| `PATCH /api/v1/admin/notifications/{id}/read` | 204. 이미 읽었으면 그대로(처음 시각 유지), 없는 id 는 404 |
| `POST /api/v1/admin/notifications/read-all?audience=` | 204. `audience` 를 비우면 둘 다 |
| `GET /api/v1/admin/notifications/stream?audience=` | 실시간 연결(아래) |

읽음은 전역 하나다 — 관리자 계정이 하나(`sub="admin"`)라 사람을 가르지 않는다. 깨진 커서 · 범위 밖 값은 400 이다.

### 항목

```json
{
  "notificationId": "0199a3f2-8a10-7b21-9c32-4d5e6f708192",
  "type": "REGISTER_FAILED",
  "audience": "ADMIN",
  "severity": "URGENT",
  "status": "OPEN",
  "target": { "kind": "NONE", "id": null, "name": null },
  "metrics": { "deadLetters": 860 },
  "resolvable": true,
  "firstOccurredAt": "2026-10-26T05:02:11Z",
  "lastUpdatedAt": "2026-10-26T05:14:40Z",
  "raisedAt": "2026-10-26T05:12:00Z",
  "resolvedAt": null,
  "readAt": null
}
```

| 칸 | 뜻 |
| --- | --- |
| `target` | `kind` 는 `NONE` · `PRODUCT` · `DRAW` · `SERVICE`. `NONE` 이면 `id` 는 `null`. `name` 은 상품 · 회차 이름이고, 받지 못했으면(비공개 · 미완료 상품, 조회 실패) `null` 이다 — 프론트는 id 로 보인다 |
| `metrics` | type 마다 정한 숫자 · 시각(`admin-alert-events.md` · `admin-alert-grafana.md`). 값이 없으면 `null` |
| `resolvable` | `false` 면 한 번 소식이다 |
| `raisedAt` | 마지막으로 새 알림으로 올린 시각. 목록이 이 순서다 |
| `readAt` | 읽은 시각. 다시 올리면 `null` 로 돌아간다 |

다시 올린 알림은 맨 앞으로 옮겨 가므로, 쪽을 넘기는 사이에 올라간 알림은 다음 쪽에 나오지 않는다. 첫 쪽을 다시 읽으면 보인다.

### 실시간 연결

`GET /api/v1/admin/notifications/stream?audience=`(`text/event-stream`). 인증은 다른 API 와 같은 Bearer 헤더다(fetch 기반 SSE 클라이언트).

- 연결 뒤 한 주기(2초) 안에 `event: unread`(unread-count 와 같은 모양)가 온다.
- 알림이 생기거나 바뀔 때마다(숫자 갱신 · 다시 올림 · 해소 · 읽음 · 이름 채움) `event: notification`(항목 하나)이 오고, 그 뒤에 `unread` 가 온다. `audience` 를 주면 그 탭의 항목만 오고 `unread` 는 둘 다 담는다.
- 한 주기(2초)에 바뀐 알림이 100건을 넘으면(모두 읽음 같은 대량 변경) 항목마다 보내지 않고 `event: resync`(`{}`) 하나와 `unread` 를 보낸다. 받은 화면은 목록을 첫 쪽부터 새로 읽는다. 연결은 그대로다.
- 25초마다 주석 한 줄. 액세스 토큰 만료 · 최대 30분에 서버가 닫는다 — 다시 연결하고 목록을 새로 읽는다. 쓰지 못한 사건이 1,000개를 넘어도 닫는다.
- 같은 관리자 계정의 연결이 20개를 넘으면 가장 오래된 연결에 `event: replaced`(`{"reason": "MAX_PER_PRINCIPAL"}`)를 보내고 닫는다. 이 사건을 받은 탭은 다시 연결하지 않는다 — 다시 연결하면 다른 탭을 밀어내는 순환이 된다. 사건 없이 닫히면(수명 · 토큰 만료 · 배포) 다시 연결한다.
- 인스턴스당 100 연결을 넘으면 503 이다 — 화면은 unread-count 주기 조회로 돌아간다.

## 종료

member 작업 정의에는 `stop_timeout` 90 이 있어야 한다(NV-825). 소비기 셋(회원 알림 · 관리자 이벤트 · Grafana)이 멈출 때 진행 중인 롱 폴링(20초)을 차례로 기다려 종료가 약 1분 걸리고(실측 52.9초), ECS 기본(30초)을 넘는다 — 90초는 그 위의 여유다. 그보다 늦어 SIGKILL 이 와도 처리 중이던 메시지는 지우기 전이라 가시성 시간(3분) 뒤 다시 받고, 저장은 사건 단위로 멱등이다.

## 지연

알림은 처음 생길 때 최대 약 2~3분 늦게 보인다. Grafana 경보는 지표 내보내기(60초) · 규칙 평가(1분) · 정책 대기(`group_wait` 10s)를 거친다. 그 뒤 숫자 갱신과 `QUEUE_SERVER_UNHEALTHY` 승격은 약 2분마다의 재전송으로 와서, 실제보다 최대 약 2분 늦게 반영된다. 실시간 연결은 저장 뒤 2초 안에 보낸다.

## 지표

| 이름 | 뜻 | 볼 것 |
| --- | --- | --- |
| `member.admin-alerts.received{source, type, outcome}` | 받은 사건 수. `source` 는 `GRAFANA` · `EVENT`, `type` 은 아는 종류만 그대로(모르는 것은 `OTHER`), `outcome` 은 `created` · `updated` · `raised` · `resolved` · `ignored` | `OTHER` 가 보이면 type 표를 늘린다. `ignored` 가 꾸준하면 늦은 · 중복 메시지가 많다 |
| `member.admin-alerts.rejected{queue, reason}` | 계약 위반으로 DLQ 에 넣은 메시지 수 | 0 이 아니면 보내는 쪽(order · preorder · Grafana 규칙)의 버그 |
| `member.admin-alert.streams` | 이 태스크에 열린 실시간 연결 수 | 태스크당 상한(100)에 가까우면 새 연결이 503 이다 |
| `member.admin-alert.stream.closed{reason}` | 서버가 연결을 닫은 수(연결마다 한 번). `replaced` 같은 계정의 연결이 20개를 넘었다 · `overflow` 쓰지 못한 사건이 1,000개를 넘었다 · `disconnected` 쓰다 IOException(떠난 클라이언트) · `error` 쓰다 예기치 않은 예외. 수명 · 토큰 만료 · 종료로 닫은 것은 세지 않는다 | `replaced` 가 꾸준하면 탭이 너무 많거나 화면이 다시 연결을 되풀이한다. `error` 는 0 이어야 한다(WARN 로그에 스택) |
| `member.admin-alert.stream.tail` | 연결을 가진 태스크의 한 주기(바뀐 알림 읽기 · 안 읽은 수 세기) 시간. 연결이 없으면 읽지도 재지도 않는다 | 2초에 가까워지면 실시간 연결이 늦다 |

## 열린 채 남는 알림

아래 경우에는 해소가 오지 않아 알림이 열린 채 남는다. 관리자가 닫는 API 는 없다. 오래 갱신되지 않은 열린 알림을 자동으로 닫지도 않는다 — 해소 뒤 늦게 온 firing 은 다시 열지 않는 규칙과 부딪혀, Grafana 가 잠깐 멈췄다 돌아오면 진짜 알림이 무시된다.

| 경우 | 왜 남는가 | 할 일 |
| --- | --- | --- |
| Grafana 규칙의 라벨을 바꿔 type · 대상이 달라졌다 | 새 사건이 앞 사건을 닫지 못한다(`admin-alert-grafana.md` "라벨을 바꿀 때 주의할 것") | 라벨을 바꾸는 배포는 열린 관리자 알림이 없을 때 한다(`GET /api/v1/admin/notifications?status=OPEN`). 남았으면 아래 SQL 로 닫는다 |
| 해소 메시지가 재수신 한도를 넘겨 DLQ 로 갔다(DB 장애가 길었다) | 해소가 저장되지 않았다 | 원인이 풀린 뒤 DLQ 를 되돌린다(redrive). 저장은 사건 단위로 멱등이라 그사이 온 메시지와 겹쳐도 안전하다 — 이미 해소됐으면 그대로, 열려 있으면 해소한다 |
| Grafana 가 해소를 보내지 못했다(연락처 오류 · SNS 실패 · 상태를 잃음) | 해소 메시지가 없다 | 같은 type · 대상의 다음 사건이 오면 member 가 앞 사건을 닫는다. 그 전에 닫아야 하면 아래 SQL 로 닫는다 |
| `DRAW_DELAYED` 와 `DRAW_SETTLED` 를 동시에 처리했다 | 서로의 커밋 전 행을 보지 못해 SETTLED 의 해소가 DELAYED 를 지나칠 수 있다 | member 가 막는다: `DRAW_DELAYED` 를 넣은 뒤 같은 회차의 `DRAW_SETTLED` 를 잠금 읽기(`FOR SHARE`)로 다시 본다 — SETTLED 가 커밋 전이면 그 커밋을 기다렸다가 보고 해소한다. 둘 다 서로의 행을 먼저 넣은 드문 순서는 교착(1213)으로 한쪽이 되돌려지고, 그 메시지는 일시 오류로 다시 받아 같은 결과로 끝난다 |

손으로 닫기(실시간 연결이 바뀐 행을 화면에 보낸다):

```sql
UPDATE shop.admin_alerts SET status = 'RESOLVED', resolved_at = UTC_TIMESTAMP(6), changed_at = UTC_TIMESTAMP(6)
 WHERE type = '<type>' AND target_kind = '<NONE · PRODUCT · DRAW · SERVICE>' AND target_id = '<대상 id, 없으면 빈 문자열>'
   AND status = 'OPEN';
```

## 보관

해소된 지 30일이 지난 알림을 지운다(10분마다). 한 번 소식은 발생 시각부터 센다. 열린 알림은 기간과 상관없이 지우지 않는다.

## 이름

상품 · 회차 이름은 member 가 새 사건을 받을 때 공개 조회로 한 번 받아 저장한다(catalog `GET /api/v1/products/{id}`, order `GET /api/v1/draws/{id}`). 토큰을 싣지 않는다 — 큐 소비와 실시간 연결에는 실을 토큰이 없다. 비공개 · 미완료 상품은 404 라 이름이 비고, 이름이 빈 알림은 정리 작업이 하루 안에 다시 묻는다(공개로 바뀌거나 상대가 돌아오면 채워진다). 이름이 바뀌어도 저장한 이름은 그대로다.
