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

## Mock 에 보낼 때

- 멱등 키는 응모 id 다. 재시도마다 같은 키 · 같은 본문(응모 id · 회차 id · 회원 id)을 보낸다.
- Mock 의 응모 API 모양(경로 · 본문 칸 이름 · 응답)은 Mock 담당과 정한다. order 가 기대하는 것은 하나다: 같은 키로 다시 보내면 새 응모가 생기지 않고 처음 결과와 같다.
- worker 는 처리 결과를 order 에 알리지 않는다 — order 는 넘긴 뒤 응모 상태를 바꾸지 않는다.

## 마감 대조

추첨 결과를 받을 때(NV-396) order 는 "그 회차의 결제 완료 응모 수" 와 "Mock 이 받은 응모 수" 를 비교한다. Mock 이 받은 수를 어떻게 얻을지(결과에 싣거나 조회 API)는 NV-396 에서 Mock 담당과 정한다. DLQ 에 남은 메시지는 그 회차의 응모가 Mock 에 닿지 않은 것이다 — 마감 전에 다시 흘려야 한다.

## 바꿀 때

- 받는 쪽(worker)이 모르는 종류는 처리에 실패해 재시도 뒤 DLQ 로 간다. 종류를 더하거나 payload 칸을 바꿀 때는 worker 가 먼저 나간다.
- payload 칸은 지우지 않고 더하기만 한다.
