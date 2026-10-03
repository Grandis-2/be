# catalog → preorder · order 이벤트 계약

양쪽 구현의 기준이다. catalog 가 보내고(공통 모듈 `common:outbox` · `common:sqs` — 표와 이벤트 종류는 `CatalogOutboxConfig`), preorder 와 order 가 받는다. 문서와 어긋나는 쪽이 고친다.

관리자 상품 등록(`POST /api/v1/admin/products`)은 catalog 트랜잭션 하나에서 끝난다. 다른 서비스가 자기 표에 만들 값(사전예약 회차 · 배송 차수, 일반 상품의 초기 재고)은 같은 트랜잭션에서 catalog 아웃박스(`catalog_outbox_events`)에 적고, 커밋 뒤 SQS 로 보낸다. 받는 쪽이 자기 표에 행을 만들어야 그 상품이 회원에게 보인다(판매 방식별 준비, 아래).

## 전달

| 이벤트 | 큐 | 받는 쪽 |
| --- | --- | --- |
| `PREORDER_PRODUCT_REGISTERED` | `preorder-events` | preorder — 회차 · 배송 차수를 만든다 |
| `IN_STOCK_PRODUCT_REGISTERED` | `order-events` | order — 옵션별 초기 재고를 만든다 |

- **최소 한 번.** 커밋 직후 한 번 보내고, 못 보냈으면 릴레이가 다시 보낸다. 같은 메시지가 두 번 갈 수 있다 — 받는 쪽은 두 번 받아도 결과가 같아야 한다.
- **순서 보장 없음.** 한 상품에 등록 이벤트는 하나뿐이라 순서가 문제 되지 않는다.
- **메시지 속성.** `eventType` · `eventId`(문자열). preorder · order 가 보내는 메시지와 같다.

## 봉투

preorder · order 의 `EventEnvelope` 와 같은 모양이다.

```json
{
  "eventId": "6f1c2a7e-0d3b-4c8f-9a51-2b7d8e4f0c19",
  "eventType": "IN_STOCK_PRODUCT_REGISTERED",
  "aggregateType": "PRODUCT",
  "aggregateId": 42,
  "occurredAt": "2026-10-02T03:00:00.123456Z",
  "payload": { }
}
```

| 칸 | 뜻 |
| --- | --- |
| `eventId` | UUID. 같은 메시지가 두 번 오면 같은 값이다 |
| `aggregateType` | `"PRODUCT"` (대문자 — preorder 의 `"PREORDER"` 와 같은 표기) |
| `aggregateId` | 상품 id. **payload 에는 상품 id 를 다시 넣지 않는다** — 둘이 어긋날 수 있다 |
| `occurredAt` | catalog 가 아웃박스에 적은 시각(UTC) |

**payload 에 값을 싣는 이유.** preorder · order 의 다른 이벤트는 식별자만 싣고 받는 쪽이 원장을 다시 읽는다. 이 두 이벤트는 그렇게 할 수 없다 — 회차 · 차수 · 초기 재고는 catalog 표 어디에도 없고(받는 쪽 표에 들어갈 값이다), 큐를 받는 스레드에는 catalog 내부 API 를 부를 사용자 토큰도 없다. 그래서 값을 payload 에 싣고, catalog 는 아웃박스 행을 바꾸지 않는다(같은 내용을 다시 보낸다).

## `PREORDER_PRODUCT_REGISTERED` → preorder

```json
"payload": {
  "campaign": { "opensAt": "2026-10-03T03:00:00.123456Z", "closesAt": "2026-10-06T03:00:00Z" },
  "shipmentBatches": [
    { "batchNumber": 1, "positionFrom": 1, "positionTo": 100, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" },
    { "batchNumber": 2, "positionFrom": 101, "positionTo": null, "estimatedShipStart": "2026-11-08", "estimatedShipEnd": "2026-11-14" }
  ]
}
```

| 칸 | 뜻 |
| --- | --- |
| `campaign.opensAt` · `closesAt` | 회차 시각(UTC ISO-8601). **마이크로초로 잘라 싣는다** — preorder 는 `datetime(6)` 에 저장한다 |
| `shipmentBatches` | 배송 차수 전체, 관리자 요청 순서 그대로. `positionTo` 가 `null` 이면 상한 없는 마지막 차수. 날짜는 `yyyy-MM-dd` |

**받는 쪽이 할 일.**

1. 회차와 차수를 **한 트랜잭션에** 만든다. 따로 커밋하면 회차만 있고 차수가 없는 순간에 그 상품이 노출된다(catalog 는 회차 행으로 준비를 판정하고 `shipment_batches` 는 읽지 않는다).
2. **이미 회차가 있으면 아무것도 바꾸지 않는다(생성 전용).** 같은 이벤트를 두 번 받거나, 그사이 관리자가 회차 API(`PUT …/preorder-campaign` · `…/shipment-batches`)로 고친 값을 덮지 않기 위해서다.
3. catalog 내부 API 로 상품을 다시 확인하지 않는다. 이 이벤트는 catalog 가 사전예약 상품에만 보낸다. 큐 스레드에는 넘길 토큰도 없다.
4. 차수 규칙(`ShipmentBatchPlan`: 1번부터 연속 · 첫 시작 1 · 이어짐 · 상한 없는 차수 하나)이나 "오픈은 미래" 에 어긋나면 처리를 실패시킨다 — 재시도 뒤 DLQ 로 간다. catalog 는 등록 때 같은 규칙으로 먼저 거른다(차수 규칙 맞춤은 catalog 후속 작업). DLQ 로 간 상품은 회원에게 보이지 않고, 관리자가 회차 API 로 직접 넣으면 보인다(회차 API 는 회차 · 차수를 따로 저장하므로 둘 다 넣는다).

## `IN_STOCK_PRODUCT_REGISTERED` → order

```json
"payload": {
  "items": [ { "optionId": 101, "stockTotal": 5 }, { "optionId": 102, "stockTotal": 0 } ]
}
```

| 칸 | 뜻 |
| --- | --- |
| `items` | 등록한 옵션 전부의 초기 재고(일반 상품은 옵션마다 필수, 0 허용). order 의 재고 초기화 API(`POST /api/v1/admin/products/{id}/stock`) 본문의 `items` 와 같은 모양 |

**받는 쪽이 할 일.**

1. 재고 초기화와 같은 규칙으로 처리한다 — `AdminStockService.initialize` 를 그대로 부르면 쓰는 길이 하나로 끝난다. 한 트랜잭션에서 모두 되거나 모두 안 된다.
2. **행이 없는 옵션만 만들고 있는 옵션은 그대로 둔다(생성 전용).** 같은 이벤트를 두 번 받거나, 그사이 관리자가 재고 API 로 고친 값을 덮지 않는다.
3. 옵션이 그 상품의 것인지, 상품이 일반 판매인지 확인한다. catalog 에는 옵션 삭제 경로가 없으므로(판매 상태만 바뀐다) 그 상품의 옵션이 아닌 값은 계약 오류다 — 건너뛰지 말고 실패시켜 DLQ 로 보낸다.

## 판매 방식별 준비 — catalog 가 읽는 결과

catalog 는 완료 이벤트를 돌려받지 않는다. 받는 쪽이 만든 행을 읽어 등록이 끝났는지 판정한다(`ProductListingQueryRepository.isReady` — catalog 가 다른 서비스 표를 읽는 유일한 자리).

| 판매 방식 | 준비 | 회원 노출 |
| --- | --- | --- |
| 사전예약 | `preorder_campaigns` 에 그 상품 행이 있다 | 준비 · 공개 · 판매 중일 때만 목록 · 상세에 보인다 |
| 일반 | `option_inventories` 에 그 상품 옵션의 행이 하나라도 있다(초기화는 옵션 전부를 한 트랜잭션에 만든다) | 같음. 재고 0 행은 품절로 보인다 |

이 판정이 관리자 목록 · 상세 · 등록 상태 조회(`registration.completed`)와 내부 조회 API 의 `registrationCompleted` 다.

**준비가 안 될 때 원인은 둘이다.** catalog 가 아직 보내지 못했거나, 받는 쪽이 거절했다(DLQ).
- catalog 쪽: `catalog_outbox_events.published_at IS NULL` 인 행이 남는다. 보낼 때마다 실패하면 `아웃박스 발행 실패 — 릴레이가 다시 보낸다 outboxEventId=… eventType=…` 경고 로그가 남는다(커밋 직후 발행 · 릴레이 모두 — catalog 에는 지표 수집이 없어 미발행 건수 지표는 없다). 큐 보내기 권한(IAM)이 빠졌거나 큐가 없으면 모든 등록이 여기서 멈춘다.
- 받는 쪽: 발행은 됐는데(`published_at` 있음) 행이 없다 — 그 서비스의 DLQ 를 본다.

## 보관

발행된 행을 지우는 규칙은 아직 없다. 릴레이는 `(published_at, publish_attempts, id)` 인덱스로 미발행 행만, 정렬 없이 가져갈 만큼만 읽는다(발행 20만 · 미발행 300 · 한 번에 100건에서 인덱스로 100행만 읽고 정렬 없음, MySQL 8.4.11 · 2026-10-03 실측 — 같은 데이터에서 `(published_at, id)` 는 미발행 300행을 모두 읽어 정렬했다). 보관 기간은 운영 전에 정한다.

## 바꿀 때

- payload 칸을 더하는 것은 받는 쪽이 모르는 칸을 무시하는 한 호환된다. 이름을 바꾸거나 빼는 것은 받는 쪽과 함께 바꾼다.
- 이벤트 종류 이름 · 큐 이름은 `OutboundEventType` 이 정본이다.
