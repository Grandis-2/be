# order → worker 드로우 응모 전송 계약

order 가 보내고(공통 모듈 `common:outbox` · `common:sqs` — 표와 이벤트 종류는 `OrderOutboxConfig`), worker 가 받아 Mock(추첨)에 넘긴다. 문서와 어긋나는 쪽이 고친다.

회원이 응모비를 결제하면 order 는 응모를 결제 완료(`PAID`)로 바꾸는 트랜잭션에서 아웃박스(`order_outbox_events`)에 `DRAW_ENTRY_PAID` 를 적고, 커밋 뒤 SQS 로 보낸다. 결제 완료한 응모만 추첨 대상이고, 아웃박스 행은 응모마다 하나다(결제 완료 반영이 응모마다 한 번뿐이다). 전달은 최소 한 번이라 같은 메시지가 두 번 갈 수는 있다(아래).

전송 작업 표는 두지 않는다. 재시도 · 실패 보관은 SQS 가 한다 — 처리하지 못한 메시지는 가시성 지연 뒤 다시 오고, 최대 수신 횟수를 넘으면 DLQ(`draw-register-dlq`)로 간다. 로컬은 5번(`docker/floci/create-queues.sh`)이고 운영 값은 인프라 설정이다.

## 전달

| 이벤트 | 큐 | 받는 쪽 |
| --- | --- | --- |
| `DRAW_ENTRY_PAID` | `draw-register` | worker — 응모를 Mock 에 넘긴다 |

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

- 멱등 키는 응모 id 다. 재시도마다 같은 키 · 같은 본문(응모 id · 회차 id · 회원 id)을 보낸다.
- 재시도해도 결과가 같은 거절(본문 검증 실패 등)만 DLQ 로 보낸다 — 공통 소비기(`RetryingQueueConsumer`)는 예외 종류와 상관없이 다시 받기라, 바로 보내려면 worker 가 `draw-register-dlq` 에 직접 보내고 원본을 지운다(그 큐의 SendMessage 권한 포함). 408 · 429 · 5xx · 시간 초과 · **모르는 회차**는 재시도한다(가시성 지연) — 회차 등록을 따로 두면 순서 보장이 없어 응모가 먼저 도착할 수 있다.
- worker 는 처리 결과를 order 에 알리지 않는다 — order 는 넘긴 뒤 응모 상태를 바꾸지 않는다.

**Mock 담당과 정할 것(열린 질문).**
- 응모 API 모양(경로 · 본문 칸 이름 · 응답). order 가 기대하는 것은 하나다: 같은 키로 다시 보내면 새 응모가 생기지 않고 처음 결과와 같다.
- Mock 이 회차를 먼저 알아야 응모를 받는가. 회차는 order 가 만들고, 지금 Mock 에 회차를 등록하는 경로가 이 계약에 없다. 처음 보는 `drawId` 를 거절하면 모든 응모가 DLQ 로 간다 — 회차 등록 이벤트를 따로 둘지, 처음 보는 회차를 응모로 만들지 정한다.
- Mock 이 받은 응모 수를 order 가 어떻게 아는가(아래 대조).

## 마감 대조

**추첨 전**, 마감 뒤 그 회차에 결제 확인 중(`AUTHORIZING`) 응모가 0건이 되면 order 는 "그 회차의 결제 완료 응모 수" 와 "Mock 이 받은 응모 수" 를 비교하고, **다르면 추첨하지 않는다**(NV-396). 추첨 뒤에는 빠진 응모를 되돌릴 수 없고 환불도 없다.

다를 때 원인은 셋이다 — 바로 DLQ 라고 보지 않는다. 기다렸다 다시 대조하되, 오래 맞지 않으면 아래를 본다.
- order 가 아직 못 보냈다: `order_outbox_events` 에 `event_type = 'DRAW_ENTRY_PAID'` 이고 `published_at IS NULL` 인 행이 남는다. 보낼 때마다 실패하면 `아웃박스 발행 실패 — 릴레이가 다시 보낸다` 경고 로그가 남는다.
- 보냈는데 아직 재시도 중이다: `draw-register` 에 메시지가 남아 있다(가시성 지연).
- worker · Mock 이 거절했다: `draw-register-dlq` 에 남는다. 원인을 고친 뒤 다시 흘린다 — 추첨 전에.

## 배포 · 운영

order 가 이 이벤트를 쓰기 전에 아래가 있어야 한다. 빠져도 order 는 뜨고 결제도 되지만, 발행이 실패해 위 "아직 못 보냈다" 에서 조용히 쌓인다(큐 주소는 처음 보낼 때 찾는다).
1. 운영 SQS 큐 `draw-register` 와 `draw-register-dlq`, 그 사이 redrive(최대 수신 횟수).
2. order 실행 역할에 `draw-register` 의 `sqs:GetQueueUrl` · `sqs:SendMessage` 권한(큐 주소를 이름으로 찾은 뒤 보낸다).
3. 환경마다 큐 이름에 접두어를 붙이면 `nova.sqs.queues.draw-register` 매핑.

**worker 가 먼저 나간다 — 첫 도입에도.** worker 가 없으면 메시지가 `draw-register` 에 쌓이기만 하고, 본 큐 보존 기간(로컬은 SQS 기본값 — DLQ 만 14일로 늘려 둠)이 지나면 DLQ 로 가지 않고 사라져 대조 때 흔적이 없다. 드로우를 열기 전에 worker 의 소비기가 떠 있어야 한다.

## 바꿀 때

- 받는 쪽(worker)이 모르는 종류는 처리에 실패해 재시도 뒤 DLQ 로 간다. 종류를 더하거나 payload 칸을 바꿀 때는 worker 가 먼저 나간다.
- payload 칸은 지우지 않고 더하기만 한다.
