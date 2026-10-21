# preorder ↔ order 이벤트 계약

사전예약의 결제 진행 · 확정과 예약 취소에 따른 주문 정리를 두 서비스가 주고받는 이벤트다. 양쪽 구현의 기준이고, 문서와 어긋나는 쪽이 고친다.
order 의 보내는 쪽은 `order/outbox`(표 `order_outbox_events`), 받는 쪽은 `order/event`. preorder 의 보내는 쪽은 `preorder/outbox`, 받는 쪽은 `preorder/event`(`PreorderEventHandler`).

## 전달

| 이벤트 | 보내는 쪽 → 큐 | 받는 쪽이 하는 일 |
| --- | --- | --- |
| `PREORDER_PAYMENT_STARTED` | order → `preorder-events` | 예약에 결제 시작 시각을 남긴다(화면의 "결제 진행 중") |
| `PREORDER_PAYMENT_CONFIRMED` | order → `preorder-events` | 예약을 확정(RESERVED)한다. 취소 중이면 결제 시각만 남긴다 |
| `PREORDER_CANCEL_REQUESTED` | preorder → `order-events` | 그 예약의 주문을 정리한다(미결제 취소 · 결제됨 환불 · 거절) |
| `PREORDER_ORDER_SETTLED` | order → `preorder-events` | 정리 결과로 외부 취소를 시작하거나(NO_ORDER · CANCELED) 예약을 되돌린다(REJECTED) |

결제 기한 만료는 이벤트로 받지 않는다 — preorder 주기 작업이 만료 취소를 시작하고, 주문 정리는 위 취소 요청(사유 `EXPIRY`)으로 간다.

- **최소 한 번.** 보내는 쪽은 업무 변경과 같은 트랜잭션에서 아웃박스에 적고, 커밋 직후 한 번 보내며 못 보냈으면 릴레이가 다시 보낸다. 같은 메시지가 두 번 갈 수 있다 — 받는 쪽은 두 번 받아도 결과가 같다(이벤트마다 아래).
- **순서 보장 없음.** 표준 큐다. 이벤트마다 순서가 뒤집혀도 되는 근거를 아래에 적는다.
- **메시지 속성.** `eventType` · `eventId`(문자열).

## 봉투

```json
{
  "eventId": "6f1c2a7e-0d3b-4c8f-9a51-2b7d8e4f0c19",
  "eventType": "PREORDER_PAYMENT_CONFIRMED",
  "aggregateType": "PREORDER",
  "aggregateId": "0199a3f2-8a10-7b21-9c32-4d5e6f708192",
  "occurredAt": "2026-10-16T03:00:00.123456Z",
  "payload": { }
}
```

| 칸 | 뜻 |
| --- | --- |
| `aggregateType` | 네 이벤트 모두 `"PREORDER"` — 정리 결과에는 주문이 없는 경우(NO_ORDER)가 있어 주문 id 를 쓸 수 없다 |
| `aggregateId` | 예약 **내부 id**. order 는 이것으로 주문을 찾는다(`orders.preorder_id`) |
| `payload.preorderId` | 예약 **공개 UUID**(`preorder_token`). preorder 는 이것으로 예약을 찾는다. order 는 받은 값을 그대로 돌려준다 |

시각 칸은 모두 UTC ISO-8601 문자열이다(`2026-10-16T03:00:00.123456Z`). 정밀도는 마이크로초까지이고 끝자리 0 은 줄여 쓸 수 있다(`03:00:00Z`).

## `PREORDER_PAYMENT_STARTED` (order → preorder)

```json
"payload": { "preorderId": "9f1c2d3e-…", "orderId": "0199a3f2-…", "startedAt": "2026-10-16T02:58:00.123456Z" }
```

| 칸 | 뜻 |
| --- | --- |
| `orderId` | 주문 id(UUID 문자열) |
| `startedAt` | 주문을 만든 시각(`orders.created_at`) |

- **언제.** 사전예약 주문을 **새로 만든** 트랜잭션에서 적는다(`PlaceOrderService`). 같은 예약으로 다시 요청해 기존 주문을 돌려준 경우(먼저 찾았든 중복 키로 부딪혔든)는 적지 않는다. 예약당 주문은 하나라(`uq_order_preorder`) 이 알림도 예약당 한 번이다.
- **중복 · 순서.** preorder 는 REGISTERED 일 때만 시각을 남기고 나머지 상태에서는 무시한다. `PREORDER_PAYMENT_CONFIRMED` 보다 늦게 와도 예약이 이미 확정(RESERVED)이라 무시되므로 해가 없다.

## `PREORDER_PAYMENT_CONFIRMED` (order → preorder)

```json
"payload": { "preorderId": "9f1c2d3e-…", "orderId": "0199a3f2-…", "paidAt": "2026-10-16T03:00:00.123456Z" }
```

| 칸 | 뜻 |
| --- | --- |
| `paidAt` | 주문의 결제 시각(`orders.paid_at`, 아래 "결제 시각") |

- **언제.** 사전예약 주문의 결제 승인을 반영한 트랜잭션에서 적는다(`PaymentResults`). 반영이 되돌아가면 알림도 없다.
- **중복.** 승인은 주문이 승인 중(AUTHORIZING)일 때만 반영되고 행 잠금 뒤 판정하므로, 승인 API 응답과 결과 이벤트가 겹치거나 결과 이벤트가 여러 번 와도 반영 · 알림은 한 번이다. 아웃박스 재전송으로 같은 메시지가 다시 가도 preorder 는 RESERVED 에서 무시한다.
- **순서.** 예약이 취소 중(CANCELING)일 때 오면 preorder 는 결제 시각만 남긴다 — 그 취소가 거절되면 확정으로 돌아갈 근거다.
- **취소가 끝난(CANCELED) 예약에 오면** preorder 는 확정하지 않는다. 결제 시각이 취소 전 확정 · 취소 중에 이미 남긴 시각과 같으면 같은 확정이 다시 온 것이라 그대로 끝낸다. 남긴 시각이 없거나 다르면 이상 결제로 한 번 기록해(`preorder_payment_anomalies`, 같은 예약 · 결제 시각은 한 번) 지표 `preorder.payment.after_cancel` 과 ERROR 로그로 알린다. 취소 전에 보낸 확정이 늦게 와도(전달 순서는 보장하지 않는다 — 결제된 주문은 사용자 취소로 환불된 뒤 CANCELED 가 먼저 닿을 수 있다) 같이 기록되므로, 이 기록만으로는 늦은 전달과 아래 "보장" 위반을 가르지 못한다. 경보가 나면 order 의 그 주문 상태(환불 여부)를 확인한다. 환불 대상은 payment 의 운영 대조가 찾는다.

### 결제 시각

`orders.paid_at`. 승인을 반영할 때 한 번 적고 바꾸지 않는다(환불로 취소돼도 남는다).

- 결과 이벤트(`ORDER_PAYMENT_SETTLED`)로 반영하면 그 이벤트의 `approvedAt`(결제사 승인 시각).
- 승인 API 응답으로 반영하면 반영한 시각 — 응답에 승인 시각이 없다. 대개 결제사 승인 직후라 몇 초 차이지만, 결과를 모르던 승인을 사용자의 재요청이 회수하면 그 재요청 시각이라 더 늦을 수 있다.
- 이 칸이 생기기 전에 결제된 주문은 승인 반영 이력(AUTHORIZING → AWAITING_CONFIRMATION)의 시각으로 채웠다(`V202610161601`).

마이그레이션과 새 버전 사이에 구버전이 반영한 승인은 칸이 비어 있다. 그때 order(거절 · 백필)는 승인 반영 이력의 시각을 읽는다(`OrderReader.findPaidAt`) — 마이그레이션이 채운 값과 같은 규칙이다. 그래서 CONFIRMED 와 SETTLED 의 `paidAt` 은 한 주문에 대해 늘 같다.

## `PREORDER_CANCEL_REQUESTED` (preorder → order)

```json
"payload": { "preorderId": "9f1c2d3e-…", "customerId": "0199…", "reason": "EXPIRY", "cancelSequence": 3 }
```

| 칸 | 뜻 |
| --- | --- |
| `reason` | `USER` · `ADMIN` · `EXPIRY` · `CAMPAIGN_CANCELED`. order 가 판정에 쓰는 것은 `EXPIRY` 하나다 |
| `cancelSequence` | 이 취소 시도의 번호. order 는 저장하지 않고 정리 결과에 그대로 돌려준다 |

order 가 하는 일(`SettlePreorderCancelService`, 주문 행을 잠근 뒤 판정):

| 주문 상태 | `EXPIRY` | 그 밖의 사유 |
| --- | --- | --- |
| 없음 | NO_ORDER | NO_ORDER |
| 결제 대기 | 취소 → CANCELED | 취소 → CANCELED |
| 승인 중(결과 모름) | 보류 — 늦춰 다시 받는다 | 보류 |
| 결제됨(출고 전) | REJECTED(`PAID`) — 환불하지 않는다(만료 경합) | 취소 중 + 환불 요청, 환불이 끝나면 CANCELED |
| 취소 중(환불 중 · 환불 실패) | 보류 | 보류 |
| 출고됨 · 배송 완료 | REJECTED(`SHIPPED`) | REJECTED(`SHIPPED`) |
| 취소됨 | CANCELED | CANCELED |

- **중복.** 전이 · 환불 요청은 상태 머신이 한 번만 하고, 정리 결과는 받을 때마다 다시 적는다. preorder 는 `cancelSequence` 로 지금 시도의 결과만 반영한다.
- **보류.** 결과를 정할 수 없으면 같은 큐에 지연을 두고 다시 보낸다(최대 15분 간격, 포기하지 않는다).

## `PREORDER_ORDER_SETTLED` (order → preorder)

```json
"payload": { "preorderId": "9f1c2d3e-…", "result": "REJECTED", "reason": "SHIPPED", "cancelSequence": 3, "paidAt": "2026-10-16T03:00:00.123456Z" }
```

| 칸 | 뜻 |
| --- | --- |
| `result` | `NO_ORDER` · `CANCELED` · `REJECTED`. 미결제 취소와 결제 주문의 환불 완료는 둘 다 `CANCELED` |
| `reason` | `REJECTED` 일 때만: `SHIPPED`(출고됨) · `PAID`(만료 취소인데 그사이 결제됨) |
| `cancelSequence` | 받은 값 그대로 |
| `paidAt` | `REJECTED` 이고 주문이 결제됐으면 **사유와 상관없이** 주문의 결제 시각. 결제되지 않았으면 `null`. 다른 결과에는 늘 `null` |

- **paidAt 을 왜 싣나.** preorder 는 거절을 받으면 실려 온 결제 시각을 먼저 남기고, 결제 확인된 예약(확정 시각이 있음)은 확정(RESERVED)으로, 아니면 REGISTERED 로 되돌린다. 거절이 승인 알림보다 먼저 닿아도 결제된 예약이 미결제로 돌아가 다시 만료되지 않게 한다.
- **읽는 때.** 주문 행을 잠근 뒤 DB 에서 다시 읽는다 — 그사이 반영된 승인(만료 경합)까지 본다.
- **비어 있는 경우.** 승인을 반영한 적이 없는 주문이다(위 "결제 시각" — 칸이 비어도 이력으로 읽는다).

## 보장: 취소(CANCELED) 뒤에는 그 주문의 결제를 승인으로 반영하지 않는다

order 가 `CANCELED` 를 보낸 주문은 이후 결제 승인을 반영하지 않고 `PREORDER_PAYMENT_CONFIRMED` 도 보내지 않는다 — order 의 상태 머신이 지키는
보장이다. 결제사에서 그 주문의 돈이 나가지 않는 것은 payment 의 시간 가정에 기대며(아래), 어긋나면 order 가 ERROR 로 드러낸다.

- **상태 머신.** 취소됨에서 나가는 전이가 없다. 승인 반영은 승인 중일 때만 된다(`OrderLedger.settlePayment`).
- **취소는 결제 결과를 모를 때 일어나지 않는다.** 미결제 취소는 결제 대기에서만 되고, 승인 중이면 보류한다. 결제 시작(승인 중 전환)은 결제 대기에서만 된다. 둘 다 주문 행을 잠근 뒤 판정한다.
- **결제 대기로 되돌아가는 것은 payment 가 그 결제창의 결과를 확정했을 때뿐이다.** 승인 API 가 결과를 모르면(타임아웃 · 5xx) 주문은 승인 중에 남고, payment 의 복구가 결과를 확정해 이벤트로 알린다. 되돌림은 그 결제창의 확정 거절 · 만료, 또는 "이 결제창은 시작될 수 없다" 는 payment 의 확언(번호 없음 · 금액 불일치)으로만 일어난다. 늦게 온 이전 결제창의 거절은 반영하지 않는다.
- **payment 의 가정.** 결제사에 보낸 적 없는 결제창만 만료하고 그 뒤 시작하지 않는다(`PendingCaptureExpiry` — 결제창 수명 35분 가정). 보낸 결제창은 결제사의 거절 응답, 또는 조회 결과와 승인 창(첫 전송 + 10분 + 여유 5분)이 닫힌 뒤의 미처리로만 실패를 확정하고, 끝내 모르면 확정하지 않고 사람에게 넘긴다(`CaptureRecovery`). 이 시간 가정이 틀리면 결제사에서는 승인됐는데 주문은 취소된 채 남을 수 있다.
- **그때는** 취소된 주문을 그대로 두고 ERROR 로 알린다(`결제가 승인됐는데 주문이 받지 못했다`). 자동 환불하지 않는다 — 운영 대조가 payment 의 "성공 결제가 있는데 결제되지 않은 주문" 으로 찾아 환불한다(D18). preorder 에는 아무것도 가지 않는다(예약은 이미 취소됐다).

## 만료를 켜는 순서

preorder 의 결제 기한 만료(`PaymentExpiryJob`, 기본 꺼짐)는 아래가 모두 운영에 나간 뒤에 켠다. 만료는 주문 쪽에서 취소가 거절된 적이 있는
예약을 다시 만료하지 않는다(`ExpiryCancelService`) — 그래서 순서가 바뀌어도 되풀이되지는 않지만, 결제됐는데 확정되지 않은 예약이 만료 →
order 거절(`PAID`, 결제 시각 없음) → REGISTERED 로 돌아간 뒤 확정되지 않은 채 남는다.

1. order 가 `PREORDER_PAYMENT_CONFIRMED` 를 보내고 거절에 `paidAt` 을 싣는다(이 문서의 order 쪽). 이것만으로도 위 예약의 만료는 결제 시각을
   실은 거절로 끝나 preorder 가 확정(RESERVED)으로 돌린다.
2. 백필: 그 전에 결제된 사전예약 주문의 확정을 preorder 에 한 번 알린다 — 결제 성공 · 환불 전(AWAITING_CONFIRMATION · PREPARING_ITEMS ·
   READY_TO_SHIP · SHIPPED · DELIVERED)인 주문마다 CONFIRMED 1건(`paidAt` 은 위 "결제 시각" 규칙). 1 이 운영에 나간 뒤 별도 변경으로 한다 —
   구버전 order 의 발행기는 모르는 이벤트 종류의 행을 보내지 못한다. 백필은 그 예약들이 한 번씩 취소 중으로 갔다 돌아오는 것(그동안 화면에
   취소 중으로 보인다)을 막고, 확정을 만료 전에 맞춰 둔다.

## 배포

- preorder 는 order 가 보내는 세 이벤트(`PREORDER_PAYMENT_STARTED` · `PREORDER_PAYMENT_CONFIRMED` · `PREORDER_ORDER_SETTLED`)를 모두 받는다 — 받는 쪽이 먼저 나가 있다.
- 새 order 가 적은 `PREORDER_PAYMENT_STARTED` · `PREORDER_PAYMENT_CONFIRMED` 행을 같이 도는 구버전 order 의 릴레이가 집으면 종류를 몰라 보내지 못하고 실패로 남긴다(행은 미발행 그대로). 새 버전의 커밋 직후 발행 · 릴레이가 보낸다 — 잃지 않고 늦을 뿐이다.
