# order ↔ worker 드로우 계약

응모 · 추첨 요청은 order 가 보내고(공통 모듈 `common:outbox` · `common:sqs` — 표와 이벤트 종류는 `OrderOutboxConfig`) worker 가 받아(`DrawHandler`) Mock(추첨)에 넘긴다. 추첨 결과는 worker 가 order 에 보낸다(`InboundEventType`). 문서와 어긋나는 쪽이 고친다.

회원이 응모비를 결제하면 order 는 응모를 결제 완료(`PAID`)로 바꾸는 트랜잭션에서 아웃박스(`order_outbox_events`)에 `DRAW_ENTRY_PAID` 를 적고, 커밋 뒤 SQS 로 보낸다. 결제 완료한 응모만 추첨 대상이고, 아웃박스 행은 응모마다 하나다(결제 완료 반영이 응모마다 한 번뿐이다). 전달은 최소 한 번이라 같은 메시지가 두 번 갈 수는 있다(아래).

전송 작업 표는 두지 않는다. 재시도 · 실패 보관은 SQS 가 한다 — 처리하지 못한 메시지는 가시성 지연 뒤 다시 오고, 최대 수신 횟수를 넘으면 DLQ(`draw-register-dlq`)로 간다. 로컬은 5번(`docker/floci/create-queues.sh`)이고 운영 값은 인프라 설정이다.

## 전달

| 이벤트 | 큐 | 받는 쪽 |
| --- | --- | --- |
| `DRAW_ENTRY_PAID` | `draw-register` | worker — 응모를 Mock 에 넘긴다 |
| `DRAW_REQUESTED` | `draw-register` | worker — Mock 이 받은 응모 수를 대조하고 추첨을 맡긴다(아래 "추첨") |
| `DRAW_RESULT_READY` | `order-events` | order — 당첨 응모에 증정 주문을 만든다(worker 가 보낸다) |

- **최소 한 번.** 커밋 직후 한 번 보내고, 못 보냈으면 릴레이가 다시 보낸다. 같은 메시지가 두 번 갈 수 있다 — Mock 에는 멱등 키(응모 id)로 보내 두 번 받아도 응모가 하나여야 한다.
- **순서 보장 없음.** 응모끼리 순서는 없다. 추첨은 마감 뒤라 순서에 기대지 않는다.
- **마감 뒤에도 온다.** 마감 직전에 시작한 결제는 마감 뒤에 결제 완료가 될 수 있고, 그 응모도 추첨 대상이다(환불이 없다). worker · Mock 은 회차 마감 시각이 지난 뒤 도착한 응모도 **추첨 전까지는 정상 응모로 받는다** — 마감 검사로 거절하면 DLQ 로 가고 아래 대조가 영영 맞지 않는다.
- **메시지 속성.** `eventType` · `eventId`(문자열). order 가 보내는 다른 메시지와 같다.

## 봉투

order 의 다른 이벤트와 같은 `EventEnvelope` 다.

```json
{
  "eventId": "6f1c2a7e-0d3b-4c8f-9a51-2b7d8e4f0c19",
  "eventType": "DRAW_ENTRY_PAID",
  "aggregateType": "DRAW_ENTRY",
  "aggregateId": "0199a3f2-8a10-7b21-9c32-4d5e6f708192",
  "occurredAt": "2026-10-11T03:00:00.123456Z",
  "payload": {
    "drawId": "0199a3f1-0000-7000-8000-000000000001",
    "customerId": "0199a3f0-0000-7000-8000-000000000002"
  }
}
```

| 칸 | 뜻 |
| --- | --- |
| `aggregateType` | `"DRAW_ENTRY"` |
| `aggregateId` | 응모 id(UUID 문자열). **Mock 에 보내는 멱등 키**다. payload 에는 다시 넣지 않는다 |
| `payload.drawId` | 회차 id(UUID 문자열). Mock 은 회차별로 추첨한다 |
| `payload.customerId` | 응모한 회원 id(UUID 문자열). 배송지 · 결제 정보는 싣지 않는다 |

**왜 값을 싣는가.** 다른 이벤트의 받는 쪽은 aggregateId 로 자기 원장을 다시 읽지만, worker 에는 order 의 응모를 읽는 길이 없다. 그래서 Mock 에 보낼 값을 payload 에 담는다. 응모 · 회차 · 회원은 결제 완료 뒤 바뀌지 않으므로 다시 읽어도 같은 값이다.

## Mock 에 보낼 때

Mock API 는 mock 저장소 `docs/api.md` 「럭키드로우」 장이다(NV-505). 처음 보는 `drawId` 로 응모가 오면 Mock 이 회차를 만들어 받으므로 회차를 따로 등록하지 않는다 — 응모가 회차보다 먼저 도착해도 된다. 응모 · 추첨 모두 같은 요청을 다시 보내면 처음 결과가 온다(재생). 그래서 worker 는 결과를 모르면 조회 없이 같은 요청을 다시 보낸다.

| 호출 | 모양 |
| --- | --- |
| 응모 | `POST /external/draw-entries` — 헤더 `Idempotency-Key` = 응모 id, 본문 `{entryId, drawId, customerId}`. 재시도마다 같은 키 · 같은 본문 |
| 회차 조회 | `GET /external/draws/{drawId}` — `entryCount` 가 받은 응모 수. 404 `NOT_FOUND` 는 받은 응모 0건 |
| 추첨 | `POST /external/draws/{drawId}/draw` — 본문 `{winnerCount, expectedEntryCount}`(기대 수 = `paidEntries`). Mock 이 회차를 잠근 채 다시 세어 다르면 409 `ENTRY_COUNT_MISMATCH`. 회차 id 가 멱등 키다(헤더 없음) |

worker 는 메시지를 아래 중 하나로 끝낸다. 재시도 · 실패 보관은 SQS 가 한다 — 지우지 않고 늦춘 메시지는 받은 횟수가 쌓여 최대 수신 횟수를 넘으면 SQS 가 DLQ(`draw-register-dlq`)로 옮긴다. 다시 보내도 결과가 같은 거절만 그 횟수를 기다리지 않고 worker 가 DLQ 에 바로 넣는다.

| 결과 | 응답 | 처리 |
| --- | --- | --- |
| 성공 | 응모 201(재생 포함) · 추첨 200 | 추첨이면 `DRAW_RESULT_READY` 를 보낸 뒤 지운다. 응모는 order 에 알리지 않는다 — order 는 넘긴 뒤 응모 상태를 바꾸지 않는다 |
| 일시 실패 | 408 · 429 · 5xx · 시간 초과 | 지우지 않고 늦춰 다시 받는다 — 받은 횟수에 따라 10초에서 15분까지(`nova.worker.draw.retry-delays`) |
| 기다림 | 받은 응모 수 ≠ `paidEntries` · 추첨 409 `ENTRY_COUNT_MISMATCH` · 404 `NOT_FOUND` | 지우지 않고 늦춰 다시 받는다 — 1분에서 1시간까지(`wait-delays`). 합(약 1시간 51분)이 아래 "결과 반영 지연" 경보보다 길어 사람이 먼저 본다 |
| 확정 거절 | 400 · 409 `CUSTOMER_ALREADY_ENTERED` · `DRAW_CLOSED` · 422 `KEY_PAYLOAD_MISMATCH` · `INVALID_WINNER_COUNT`, 계약과 다른 메시지 · 추첨 결과 | DLQ 에 바로 넣고(속성 `deadLetterReason`) 지운다. ERROR 로그 |
| 지금 부를 수 없음 | Mock 차단기 열림 · 거절 이어짐 · 동시 요청 자리 없음 · 내려가는 중 · 주소 설정 오류(404 `NO_SUCH_ENDPOINT` — 응모 0건으로 읽지 않는다 — 와 errorCode 없는 3xx · 4xx, 즉 Mock 이 아닌 앞단의 응답) | 받은 횟수를 쓰지 않게 같은 큐에 새 메시지로 돌려보낸다. 주소 설정 오류는 고칠 때까지 DLQ 로 보내지 않는다 — 이어지면 받기를 멈추고 ERROR 로 알린다(사전예약과 같은 거절 이어짐 차단) |
| 모르는 종류 | eventType 이 위 둘이 아님 | 지우지 않는다 — 그 종류를 아는 worker 가 받거나 재시도 뒤 DLQ |

- worker 는 마감을 보지 않는다(위 "마감 뒤에도 온다"). 추첨 뒤에 닿은 응모는 Mock 이 409 `DRAW_CLOSED` 로 거절해 DLQ 로 간다 — order 의 `드로우 추첨 뒤 결제 완료` ERROR 와 같은 응모다.
- Mock 호출은 사전예약 작업과 같은 차단기 · 초당 상한 · 동시 요청 자리를 쓴다(취소 전용 자리는 쓰지 못한다). 같은 Mock 이라 따로 두면 서로의 부하를 모른다. 대신 응모와 예약 등록이 한꺼번에 몰리면 자리를 나눠 쓰며 서로 기다린다(자리가 없으면 돌려보낸다).

## 추첨

order 는 마감 뒤 30초(인스턴스 사이 시계 차 여유)가 지나고 그 회차에 결제 확인 중(`AUTHORIZING`) 응모가 0건이 되면 추첨할 차례로 본다(주기 작업, 기본 30초). 마감 뒤에는 결제가 새로 시작되지 않으므로 이때의 결제 완료 응모가 추첨 대상 전부다 — 단, 시계 차가 여유보다 작고 · payment 가 되돌린 결제창을 나중에 승인하지 않고 · 수동 수정이 없을 때다.
- 결제 완료가 당첨 인원 **이하**면 추첨하지 않고 전원 당첨으로 order 가 바로 반영한다. worker 에 아무것도 보내지 않는다.
- **많으면** `DRAW_REQUESTED` 를 회차마다 한 번 보낸다.

`DRAW_REQUESTED` — 봉투의 `aggregateType` 은 `"DRAW_CAMPAIGN"`, `aggregateId` 는 회차 id(payload 에 다시 넣지 않는다).

| 칸 | 뜻 |
| --- | --- |
| `payload.paidEntries` | 그 회차의 결제 완료 응모 수(추첨 대상) |
| `payload.winnerCount` | 당첨 인원(`paidEntries` 보다 작다) |

worker 는 **추첨 전에** Mock 이 그 회차로 받은 응모 수를 `paidEntries` 와 대조하고, **다르면 추첨을 맡기지 않는다** — 추첨 뒤에는 빠진 응모를 되돌릴 수 없고 환불도 없다. 다를 때 원인은 셋이다 — 바로 DLQ 라고 보지 않는다. 메시지를 늦춰 다시 받으며(가시성 지연, 위 "기다림") 기다렸다 다시 대조하고, 끝내 맞지 않으면 최대 수신 횟수를 넘겨 DLQ 로 간다. 오래 맞지 않으면 아래를 본다.
- order 가 아직 못 보냈다: `order_outbox_events` 에 `event_type = 'DRAW_ENTRY_PAID'` 이고 `published_at IS NULL` 인 행이 남는다. 보낼 때마다 실패하면 `아웃박스 발행 실패 — 릴레이가 다시 보낸다` 경고 로그가 남는다.
- 보냈는데 아직 재시도 중이다: `draw-register` 에 메시지가 남아 있다(가시성 지연).
- worker · Mock 이 거절했다: `draw-register-dlq` 에 남는다. 원인을 고친 뒤 다시 흘린다 — 추첨 전에.
- 추첨 요청 뒤에 결제 완료가 늘었다(위 "단, …" 의 경우): 그 응모가 대조 전에 Mock 에 닿으면 Mock 이 받은 수가 `paidEntries` 보다 커서 **저절로 맞지 않고**, 추첨 뒤에 닿으면 수는 맞은 채 그 응모만 추첨에서 빠진다. order 는 그 응모에 `드로우 추첨 뒤 결제 완료` ERROR 를 남긴다 — 사람이 회차를 정리한다(늦은 응모는 추첨에서 빼고 수동 환불).

같으면 Mock 에 `winnerCount` 명 추첨을 맡기고(기대 수를 함께 보내 Mock 이 대조와 추첨을 한 번에 한다), 결과를 `DRAW_RESULT_READY` 로 `order-events` 에 보낸다. 같은 요청을 두 번 받아도 추첨은 한 번이다(회차 id 가 멱등 키) — 결과를 보낸 뒤 원본을 지우기 전에 실패해도 다시 받아 같은 결과를 보낸다.

`DRAW_RESULT_READY` — 봉투의 `aggregateType` 은 `"DRAW_CAMPAIGN"`, `aggregateId` 는 회차 id.

| 칸 | 뜻 |
| --- | --- |
| `payload.winnerEntryIds` | 당첨 응모 id 목록(UUID 문자열) — 정확히 `winnerCount` 개, 겹치지 않게, 그 회차의 결제 완료 응모 중에서 |

order 는 받으면 한 트랜잭션에서 당첨 응모마다 응모 때 배송지로 0원 주문(`source = DRAW`, 결제 확인 대기로 생김)을 만들고, 당첨분 재고를 판매로 옮긴다(당첨 수가 늘 당첨 인원이라 남는 확보는 없다 — 반납은 전원 당첨에서만 생긴다). 같은 결과를 두 번 받아도 한 번이다(이미 반영했는데 다른 목록이 오면 반영하지 않고 ERROR). 요청하지 않은 회차 · 개수가 다름 · 같은 응모 두 번 · 그 회차의 결제 완료 응모가 아님은 계약 위반이라 반영하지 않는다(처리 실패 → 재시도 뒤 order-events DLQ).

## 배포 · 운영

마감 뒤 1시간이 지나도 결과를 반영하지 못한 회차는 order 가 10분마다 `드로우 결과 반영 지연` ERROR 로 알린다(요청 시각 · 결제 확인 중 수 · 결제 완료 수) — 마감 뒤 하루까지만, 그 뒤로는 진행 조회로 본다. 추첨 요청이 DLQ 로 갔거나 worker 가 없거나, 결제가 끝나지 않은 경우다 — 관리자 진행 조회(`GET /api/v1/admin/draws/{drawId}/progress`)로도 본다.

드로우를 열기 전에 아래가 있어야 한다(nova-infra `terraform/application/{order,worker}/service.yml`). order 쪽이 빠지면 order 는 뜨고 결제도 되지만 발행이 실패해 위 "아직 못 보냈다" 에서 조용히 쌓인다(큐 주소는 처음 보낼 때 찾는다).
1. 운영 SQS 큐 `draw-register` 와 `draw-register-dlq`, 그 사이 redrive(최대 수신 횟수 — 운영 5).
2. order 실행 역할에 `draw-register` 보내기(`sqs:GetQueueUrl` · `sqs:SendMessage`).
3. worker 실행 역할에 `draw-register` 받기 · 보내기(돌려보내기), `order-events` 보내기, `draw-register-dlq` 보내기(확정 거절 — `queues.dlq_send`).
4. 환경마다 큐 이름에 접두어를 붙이면 `nova.sqs.queues.draw-register` · `nova.sqs.queues.draw-register-dlq` 매핑(운영은 접두어가 없다).

**worker 가 먼저 나간다 — 첫 도입에도.** worker 가 없으면 메시지가 `draw-register` 에 쌓이기만 하고, 본 큐 보존 기간(로컬은 SQS 기본값 — DLQ 만 14일로 늘려 둠)이 지나면 DLQ 로 가지 않고 사라져 대조 때 흔적이 없다. 드로우를 열기 전에 worker 의 소비기가 떠 있어야 한다.

## 바꿀 때

- 받는 쪽(worker)이 모르는 종류는 처리에 실패해 재시도 뒤 DLQ 로 간다. 종류를 더하거나 payload 칸을 바꿀 때는 worker 가 먼저 나간다.
- payload 칸은 지우지 않고 더하기만 한다.
