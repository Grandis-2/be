# order → catalog 이벤트 계약

양쪽 구현의 기준이다. order 가 보내고(공통 모듈 `common:outbox` · `common:sqs` — 표와 이벤트 종류는 `OrderOutboxConfig` · `OutboundEventType`), catalog 가 받는다. 문서와 어긋나는 쪽이 고친다.

catalog 는 판매 준비(일반 판매 상품은 "그 상품 옵션의 재고 행이 있는가")를 목록 · 검색의 페이징 조건으로 쓴다. 그 조건은 SQL 안에 있어야 하므로 API 로 그때그때 물을 수 없다. 그래서 order 가 재고 행을 만들 때 이 이벤트를 보내고, catalog 는 자기 표에 준비 여부를 남긴다. 남은 수량은 이벤트가 아니라 `GET /internal/stocks`(`order-internal.md`)로 읽는다.

## 전달

| 이벤트 | 큐 | 받는 쪽 |
| --- | --- | --- |
| `IN_STOCK_PRODUCT_READY` | `catalog-events` | catalog — 그 상품을 판매 준비로 남긴다 |

- **최소 한 번.** 커밋 직후 한 번 보내고, 못 보냈으면 릴레이가 다시 보낸다. 같은 메시지가 두 번 갈 수 있다.
- **메시지 속성.** `eventType` · `eventId`(문자열). order 가 보내는 다른 메시지와 같다.
- **큐가 없을 때.** 보내기에 실패한 행은 아웃박스에 미발행으로 남고 릴레이가 계속 다시 보낸다. 유실되지 않지만, 큐가 생길 때까지 order 로그에 발행 실패가 이어진다.

## 봉투

catalog 가 보내는 봉투(`catalog-events.md` "봉투")와 같은 칸이다.

```json
{
  "eventId": "6f1c2a7e-0d3b-4c8f-9a51-2b7d8e4f0c19",
  "eventType": "IN_STOCK_PRODUCT_READY",
  "aggregateType": "PRODUCT",
  "aggregateId": "0199a3f2-8a10-7b21-9c32-4d5e6f708192",
  "occurredAt": "2026-10-17T03:00:00.123456Z",
  "payload": { }
}
```

| 칸 | 뜻 |
| --- | --- |
| `eventId` | UUID. 같은 메시지가 두 번 오면 같은 값이다 |
| `aggregateType` | `"PRODUCT"`(대문자 — catalog 봉투와 같은 표기) |
| `aggregateId` | 상품 id(`products.id`, UUID 문자열) |
| `occurredAt` | order 가 아웃박스에 적은 시각(UTC) |
| `payload` | 빈 객체. 칸을 더하지 않는다 — 수량은 API 로 읽는다 |

## `IN_STOCK_PRODUCT_READY` → catalog

**언제.** 일반 판매 상품의 옵션 재고 행(`option_inventories`)을 새로 만든 트랜잭션이 같은 트랜잭션에서 아웃박스에 한 건 적는다. 한 요청이 옵션 여러 개의 행을 만들어도 한 건이다. 재고 행을 만드는 길은 모두 `AdminStockService` 를 지나므로 빠지는 경로가 없다.
- 관리자 재고 설정(`PUT /api/v1/admin/products/{productId}/stock`)이 없던 옵션의 행을 만들 때
- 재고 초기화(`POST` 같은 경로)
- catalog 의 `IN_STOCK_PRODUCT_REGISTERED` 처리(`catalog-events.md`) — 사전예약 → 일반 전환도 이 길이다

행을 하나도 만들지 않은 호출(옵션 행이 이미 다 있음)은 보내지 않는다. 요청이 실패해 롤백되면(옵션 소속 400 · `STOCK_BELOW_COMMITTED` 409 등) 행도 이벤트도 남지 않는다.

**준비는 되돌아가지 않는다.** 재고 행을 지우는 길이 없다(10/17 dev 기준). 그래서 한 번 준비된 상품은 계속 준비 상태이고, 이 계약에 "준비 해제" 이벤트는 없다. 행을 지우는 길이 생기면 이 계약을 먼저 고친다.

**처음일 때만이 아니라 행을 만들 때마다 보낸다.** 준비는 한 번 참이면 거짓이 되지 않으므로 같은 상품의 이벤트가 여러 번 와도 결과가 같다. 그래서 order 는 이전 상태를 따로 읽지 않는다. 대가로, 상품에 옵션을 더해 그 행을 만들 때마다 한 건씩 더 간다.

**받는 쪽.**
- 그 상품을 판매 준비로 표시한다. 이미 표시돼 있으면 아무것도 하지 않는다(중복 · 재전송 무해). 늦게 와도, 여러 번 와도 결과가 같으므로 순서를 따지지 않는다.
- 상품 행은 늘 있다. order 는 catalog 표에서 그 상품을 확인한 트랜잭션에서 보내고, catalog 는 상품을 소프트 삭제(`deleted_at`)한다. 지운 상품이어도 표시만 남기면 된다 — 목록은 `deleted_at` 으로 이미 거른다.
- 판매 방식은 다시 확인하지 않아도 된다. order 는 일반 판매 상품에만 재고 행을 만든다(사전예약이면 `STOCK_NOT_TRACKED`).

## 배포 순서

1. **nova-infra.** `catalog-events` 큐와 DLQ, order 역할의 `sqs:SendMessage`, catalog 역할의 수신 권한을 만든다. 원본 큐 보관 기간은 다른 큐와 같은 기본값(4일)이다 — 소비자가 나오기 전에 쌓였다가 지워지는 메시지는 3 의 채우기가 메운다. 큐 없이 order 가 먼저 나가면 위 "큐가 없을 때" 처럼 아웃박스에 남아 다시 보낸다(유실은 아니다).
2. **order(NV-630).** 이벤트를 보내고 `GET /internal/stocks` 를 연다. catalog 는 아직 `option_inventories` 를 직접 읽으므로 화면 동작은 바뀌지 않는다.
3. **catalog.** 소비자와 `products` 의 준비 칸을 만들고, **그 칸을 만드는 마이그레이션에서 `option_inventories` 를 한 번 읽어 기존 상품을 채운다**(재고 행이 하나라도 있는 일반 판매 상품). 채우기는 실행되는 시점의 재고 행을 읽으므로, 그때까지 준비된 상품은 이벤트가 큐에 남았든 4일이 지나 지워졌든 모두 채워진다. 그 뒤 새 이미지의 소비자가 뜰 때까지 몇 분 사이의 메시지만 큐에 남고, 보관 기간 안에 소비된다. 지킬 조건은 둘이다.
   - 채우기는 2 뒤에 돈다. 그 전이면 채우기와 order 배포 사이에 만든 재고 행은 이벤트도 없어 빠진다.
   - 목록 · 검색의 준비 판정을 새 칸으로 바꾸기 전에 채운다. 그다음 목록 품절 · 상세 수량 · 증정품 후보의 직접 읽기를 `GET /internal/stocks` 로 바꾼다.
