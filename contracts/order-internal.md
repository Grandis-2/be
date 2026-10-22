# order ↔ catalog 내부 API 계약

order 와 catalog 가 서로 부르는 내부 API 를 담는다(preorder 가 부르는 예약 취소 가능 여부는 여기 없다). 양쪽 구현의 기준이고, 문서와 어긋나는 쪽이 고친다.

- catalog → order: `GET /internal/order-items/{orderItemId}` — 리뷰 작성용 주문상품. order 가 응답을 만들고(`InternalOrderItemController` · `OrderQueryService.findItem`), catalog 의 리뷰 작성이 소비한다.
- catalog → order: `GET /internal/order-items/delivered` — 리뷰를 쓸 수 있는 주문상품 목록의 재료. order 가 응답을 만들고(`InternalOrderItemController` · `OrderQueryService.findDeliveredPurchaseItems`), catalog 의 리뷰 작성 가능 목록이 소비한다.
- order → catalog: `GET /internal/options` — 장바구니용 옵션 일괄 조회. catalog 가 응답을 만들고(`InternalOptionController` · `ProductOptionsQueryService.findOptions`), order 의 장바구니가 소비한다.
- catalog → order: `GET /internal/draws/gift-products/{productId}` — 상품 삭제의 드로우 증정품 확인. order 가 응답을 만들고(`InternalDrawController` · `DrawQueryService.giftInProgress`), catalog 의 상품 삭제(`ProductDeleteService`)가 소비한다.
- catalog → order: `GET /internal/stocks` — 옵션별 남은 수량. order 가 응답을 만들고(`InternalStockController` · `StockQueryService.findAvailable`), catalog 의 상품 목록(품절) · 상세(남은 수량) · 관리자 증정품 후보(수량)가 소비한다. **토큰 없이 부른다.** 판매 준비(재고 행 유무)는 이 API 가 아니라 이벤트로 받는다(`order-catalog-events.md`).

## `GET /internal/order-items/{orderItemId}` — 리뷰 작성용 주문상품 (내부 전용)

**노출.** `/internal/**` 는 공개 라우팅(ALB)에 두지 않는다 — 인프라에서 병행하고 운영 활성화 전에 차단을 확인한다(preorder-internal.md 와 같은 조건). 이 엔드포인트는 USER 토큰과 주인 확인을 요구해 공개 경로로 새어도 본인 주문상품만 보이지만, 내부 전용 계약이므로 공개하지 않는다. OpenAPI 문서에서도 기본으로 빠진다(`nova.openapi.include-internal`).

**호출 주체.**

| 언제 | 누가 | 실린 JWT | 목적 |
| --- | --- | --- | --- |
| 회원이 리뷰를 쓸 때 한 번(캐시하지 않음) | catalog 리뷰 작성 | 회원(USER) | "이 회원의 주문상품인가, 배송이 끝났는가, 어떤 주문 출처인가" 를 판정할 사실 |

**인증.** 호출자가 받은 회원 토큰을 그대로 싣는다(`Authorization: Bearer {accessToken}`). order 는 `common:security` 필터로 검증하고 **USER 만** 받는다 — ADMIN 토큰은 회원 id 가 없어 403 이다(`@CurrentCustomerId`).

**요청.** 본문 없음. `orderItemId` 는 `order_items.id`(UUID). UUID 가 아니면 400.

**응답 200.** 봉투는 `common:web` 의 `ApiResponse` 그대로.

```json
{
  "success": true,
  "data": {
    "orderItemId": "0199a3f3-1b20-7d40-a051-6f7081920a1b",
    "orderId": "0199a3f3-1b1f-7d3f-a050-6f7081920a1a",
    "productId": "0199a3f2-8a10-7b21-9c32-4d5e6f708192",
    "optionId": "0199a3f2-8a11-7c22-8d33-5e6f70819203",
    "optionTitle": "블랙 / 256GB",
    "orderStatus": "DELIVERED",
    "orderSource": "BUY_NOW"
  },
  "error": null,
  "timestamp": "2026-10-06T06:00:00Z",
  "traceId": "…"
}
```

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `orderItemId` | string(UUID) | `order_items.id` |
| `orderId` | string(UUID) | `orders.id` — 주문 API 의 `orderId` 와 같은 값이다 |
| `productId` | string(UUID) | `order_items.product_id` |
| `optionId` | string(UUID) | `order_items.option_id` |
| `optionTitle` | string | 주문 당시 옵션명(`order_items.option_title_snapshot`). 상품이 바뀌어도 그대로다 |
| `orderStatus` | string | `orders.status` 이름 그대로(AWAITING_PAYMENT · AUTHORIZING · AWAITING_CONFIRMATION · PREPARING_ITEMS · READY_TO_SHIP · SHIPPED · DELIVERED · CANCELING · CANCELED) |
| `orderSource` | string | `orders.source` 이름 그대로(PREORDER · BUY_NOW · CART · DRAW). DRAW 는 럭키 드로우 당첨 증정 0원 주문 |

**판정은 catalog 가 한다.** 배송 완료 여부, 사전예약 주문 · 드로우 당첨 주문 제외, 주문상품 1건당 리뷰 1개는 catalog 가 가린다. order 는 판정하지 않고 사실만 돌려준다 — 배송 전 주문상품도 200 이다.

**현재 order 가 만드는 주문은 PREORDER · BUY_NOW · CART · DRAW 다.** 리뷰를 쓸 수 있는 주문상품은 배송 완료된 CART · BUY_NOW 주문의 것이다.
BUY_NOW 는 상세페이지 바로 구매 주문이다(장바구니와 무관, 옵션 하나). 만들 때(`POST /api/v1/orders`, `source=BUY_NOW`) `Idempotency-Key` 헤더(UUID)가 필요하다 — 화면이 누름마다 하나 만들고 재전송에는 같은 키를 싣는다. 같은 회원 · 같은 키에 같은 내용(옵션 · 수량 · 보증 수량 · 배송지)이면 200 `reused=true` 와 먼저 만든 주문(지금 상태 그대로 — 이미 취소 · 만료 · 결제됐을 수 있으니 화면은 `status` 를 보고 결제로 넘긴다), 내용이 다르면 422 `KEY_PAYLOAD_MISMATCH`, 헤더가 없으면 400 `IDEMPOTENCY_KEY_REQUIRED`, UUID 가 아니면 400 `VALIDATION_FAILED` 다. 나중에 CORS 를 켜면(NV-407) 허용 헤더에 `Idempotency-Key` 를 넣는다.

**오류.** 공통 봉투 그대로.

| 상태 | 코드 | 언제 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | `orderItemId` 가 UUID 가 아님 |
| 401 | `UNAUTHENTICATED` | 토큰 없음 · 헤더 모양 이상 · 만료 · 서명 · `aud` 불일치 · 폐기된 토큰 |
| 403 | `FORBIDDEN` | USER 가 아닌 토큰(관리자) |
| 404 | `ORDER_ITEM_NOT_FOUND` | 없는 주문상품 · **남의 주문상품** — 존재를 드러내지 않으려고 같은 코드로 답한다 |

**404 는 코드로 가른다.** catalog 는 `error.code` 가 `ORDER_ITEM_NOT_FOUND` 일 때만 "구매 내역 없음"으로 판정한다. 그 밖의 404(공통 `NOT_FOUND` — 경로 · 배포 불일치)와 403(경로가 `/internal/**` 밖으로 어긋나면 `FORBIDDEN`)은 연동 오류로 5xx 와 같게 다룬다 — catalog 는 늘 USER 토큰을 실으므로 403 이 정상 업무 응답일 일이 없다. 404 를 모두 "없음"으로 읽으면 경로 하나가 어긋났을 때 모든 리뷰 작성이 조용히 거절되고 장애로 드러나지 않는다.

**폐기 조회 실패.** 이 경로는 order 의 `auth.revocation-check.fail-closed-paths` 에 없다 — Redis 장애로 폐기 여부를 못 보면 통과(경고 · 지표만)하고 retryable 401 은 나오지 않는다. 폐기된 토큰으로 볼 수 있는 것도 본인 주문상품의 사실뿐이다.

5xx 나 응답 지연은 catalog 가 회원에게 "잠시 후 다시" 로 답한다(catalog 쪽 읽기 1초).

## `GET /internal/order-items/delivered` — 배송 완료된 구매 주문의 주문상품 (내부 전용)

**노출 · 인증.** 위 주문상품 API 와 같다 — 회원(USER) 토큰을 그대로 싣고, ADMIN 은 403, 토큰이 없으면 401 이다. 토큰 주인의 것만 돌려준다.

**호출 주체.**

| 언제 | 누가 | 실린 JWT | 목적 |
| --- | --- | --- | --- |
| 회원이 "리뷰 쓰기" 목록을 볼 때마다(캐시하지 않음) | catalog 리뷰 작성 가능 목록 | 회원(USER) | 리뷰를 쓸 수 있는 주문상품 후보 |

**요청.** 본문 · 파라미터 없음.

**응답 200.**

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "orderItemId": "0199a3f3-1b20-7d40-a051-6f7081920a1b",
        "orderId": "0199a3f3-1b1f-7d3f-a050-6f7081920a1a",
        "productId": "0199a3f2-8a10-7b21-9c32-4d5e6f708192",
        "optionId": "0199a3f2-8a11-7c22-8d33-5e6f70819203",
        "productTitle": "갤럭시 S26",
        "optionTitle": "블랙 / 256GB",
        "imageUrl": "https://…/a.webp",
        "quantity": 1,
        "lineAmount": 1250000,
        "orderedAt": "2026-10-06T06:00:00Z"
      }
    ],
    "truncated": false
  }
}
```

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `items` | array | 배송 완료(DELIVERED)된 장바구니 · 바로 구매(CART · BUY_NOW) 주문의 주문상품. 주문 최신순, 한 주문 안은 주문상품 id 순 |
| `items[].productTitle` · `optionTitle` | string | 주문 당시 이름(스냅샷) |
| `items[].imageUrl` | string \| null | 주문 때 그 옵션의 사진. 사진을 남기기 전에 만든 주문 · 사진이 없는 옵션은 null |
| `items[].lineAmount` | number | 단가 × 수량 + 보증가 × 보증 수량 |
| `items[].orderedAt` | string | 주문 시각(`orders.created_at`) |
| `truncated` | boolean | 최근 500 주문까지만 읽는다. 배송 완료 주문이 그보다 많아 오래된 것이 빠졌으면 true |

**판정은 catalog 가 한다.** 출처 · 상태로 거르는 것까지만 order 가 하고, 이미 리뷰를 쓴 주문상품 · 사전예약 상품을 빼는 것은 catalog 가 한다.

## `GET /internal/options` — 장바구니용 옵션 일괄 조회 (내부 전용)

**노출.** 위 주문상품 API 와 같은 조건이다 — `/internal/**` 는 공개 라우팅에 두지 않고, OpenAPI 문서에서도 기본으로 빠진다. 이 엔드포인트는 USER 토큰으로 **비공개 · 판매 중지 상품까지** 돌려주므로 공개 경로 차단은 운영 필수 조건이다.

**호출 주체.**

| 언제 | 누가 | 실린 JWT | 목적 |
| --- | --- | --- | --- |
| 장바구니 조회 · 담기 · 장바구니 주문 생성 | order 장바구니 | 회원(USER) | 줄마다 표시할 상품 · 옵션 정보와 "살 수 있는가" 를 가릴 사실, 주문 스냅샷(가격 · 이름 · 모델명 · 사진 · 보증) |
| 바로 구매 주문 생성 | order 주문 | 회원(USER) | 장바구니 주문과 같은 판정 · 주문 스냅샷 |
| 럭키 드로우 회차 만들기 | order 드로우(관리자) | 관리자(ADMIN) | 증정품 옵션이 줄 수 있는 것인가, 회차 스냅샷(이름 · 모델명 · 사진) |

**인증.** 호출자가 받은 회원 토큰을 그대로 싣는다(`Authorization: Bearer {accessToken}`). catalog 는 `common:security` 필터로 검증하고 USER · ADMIN 을 받는다(`/internal/**` 규칙 — 그 밖의 역할은 403).

**요청.** 본문 없음. `ids` 에 옵션 id(`product_options.id`, UUID)를 쉼표로 잇거나(`ids=a,b`) 파라미터를 반복한다(`ids=a&ids=b`) — 둘은 같다.
- 같은 id 는 한 번으로 센다. 중복을 뺀 개수가 1~50 이어야 한다(장바구니 최대 줄 수 50). 비었거나 · 없거나 · 51개 이상 · UUID 가 아니거나 · 빈 원소(`ids=a,` 의 끝 쉼표)가 있으면 400(`field=ids`).

**응답 200.** 봉투는 `common:web` 의 `ApiResponse` 그대로. 결과는 **요청 순서**(같은 id 는 처음 자리에 한 번)이고, **없는 옵션은 빠진다** — 삭제한 상품(소프트 삭제)의 옵션도 없는 옵션이다. 호출자는 빠진 id 를 "판매 종료" 로 판정한다.

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "optionId": "0199a3f2-8a11-7c22-8d33-5e6f70819203",
        "productId": "0199a3f2-8a10-7b21-9c32-4d5e6f708192",
        "productTitle": "아이폰 17",
        "modelName": "A3104",
        "optionTitle": "블랙 / 256GB",
        "sku": "BLK-256",
        "price": 1250000,
        "listPrice": 1250000,
        "optionStatus": "ACTIVE",
        "saleMode": "IN_STOCK",
        "productStatus": "ACTIVE",
        "visible": true,
        "registrationCompleted": true,
        "warranty": { "offered": true, "surcharge": 199000 },
        "imageUrl": "https://img.example/black-0.jpg"
      }
    ]
  },
  "error": null,
  "timestamp": "2026-10-09T06:00:00Z",
  "traceId": "…"
}
```

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `optionId` | string(UUID) | `product_options.id` |
| `productId` | string(UUID) | 옵션의 상품 |
| `productTitle` | string | 상품 이름(지금 값) |
| `modelName` | string | 상품 모델명(지금 값, 예: A3104). 장바구니 표시 · 주문 스냅샷(`order_items.model_name_snapshot`) · 드로우 회차 스냅샷(`draw_campaigns.model_name_snapshot`, 당첨 주문이 옮긴다)에 쓴다 |
| `optionTitle` | string | 옵션 표시명(지금 값, 예: 블랙 / 256GB) |
| `sku` | string | 옵션 SKU |
| `price` | number | 회원이 내는 옵션 가격(정수 원) — 정가(기본가 + 고른 값의 추가금)에 상품 할인율을 걸어 100원 단위로 버린 값. 할인율 0 이면 정가. 보증 추가금은 할인하지 않는다 |
| `listPrice` | number | 옵션 정가(정수 원) — 기본가 + 고른 값의 추가금. 할인율 0 이면 `price` 와 같고, 보증 추가금은 넣지 않는다. `price` 와 같은 조회에서 나온다(같은 시점). order 는 장바구니 표시(할인 금액 계산)에만 쓰고, 이 칸을 싣기 전 catalog 에서 빠져 오면 null 로 읽는다 |
| `optionStatus` | string | 옵션 판매 상태(ACTIVE · PAUSED) |
| `saleMode` | string | 상품 판매 방식(IN_STOCK · PREORDER) |
| `productStatus` | string | 상품 판매 상태(ACTIVE · PAUSED) |
| `visible` | boolean | 상품 공개 여부. 늘 실린다 |
| `registrationCompleted` | boolean | 판매 방식별 준비 — 일반은 order 의 판매 준비 이벤트를 catalog 가 받았다(`products.stock_ready_at`). 사전예약은 늘 `true` 다 — catalog 는 이 응답을 만들며 preorder 를 부르지 않는다(preorder 접수가 같은 칸을 담은 1.3 을 동기로 부르므로 순환 호출이 된다). order 는 장바구니 · 드로우 증정품에서 사전예약을 판매 방식으로 따로 거절하므로 담기 · 증정은 그대로 막힌다 — 다만 준비 전 사전예약 옵션의 장바구니 담기는 404 대신 `PREORDER_NOT_CARTABLE` 이 된다(노출을 먼저 본다) — 공개됐지만 준비 전인 사전예약 상품이 옵션 id 로 있다는 것이 드러난다(둘 다 거절이라 받아들였다). 늘 실린다 |
| `warranty.offered` · `warranty.surcharge` | boolean · number | 상품의 보증 설정(옵션 문서의 `warranty`). 제공하지 않으면 `false` · `0` |
| `imageUrl` | string \| null | 그 옵션의 사진 — 옵션이 고른 색상의 첫 장. 색상 축이 없거나 그 색상에 사진이 없으면 상품 썸네일(첫 색상의 첫 장, 색상이 없으면 기본 사진의 첫 장). 없으면 null. 1000자 이하(catalog 가 사진 주소를 받을 때 지킨다) — 장바구니 표시 · 주문 스냅샷(`order_items.image_url_snapshot`, 넘으면 주문이 실패한다 — 제목과 같다) · 드로우 회차 스냅샷(`draw_campaigns.image_url_snapshot`, 당첨 주문이 옮긴다)에 쓴다 |

**판정은 order 가 한다.** 사전예약 제외 · 판매 중지 · 비공개 · 재고 · 보증 미제공 여부를 order 가 이 칸들로 가린다. catalog 는 비공개 · 판매 중지 · 사전예약 옵션도 200 으로 사실만 돌려준다.

**한 시점.** 옵션 · 상품 · 노출 칸(`visible` · `productStatus` · `registrationCompleted`)은 한 문장으로 읽는다(`ProductOptionsQueryRepository.findOptionsWithProducts`) — 트랜잭션 없이 한 시점이다. 따로 읽으면 사이에 커밋된 판매 중지 · 공개 전환이 섞여 한순간도 없던 조합이 나온다. 두 번째 읽기를 더하면 이 보장이 깨지므로, 칸을 더할 때도 같은 문장에 더한다.

**오류.** 공통 봉투 그대로.

| 상태 | 코드 | 언제 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | `ids` 가 비었거나 · 없거나 · 중복을 뺀 개수가 50 을 넘거나 · UUID 가 아니거나 · 빈 원소가 있음(`field=ids`) |
| 401 | `UNAUTHENTICATED` | 토큰 없음 · 만료 · 서명 · 폐기된 토큰 |
| 403 | `FORBIDDEN` | USER · ADMIN 이 아닌 토큰 |

**폐기 조회 실패.** 이 경로는 catalog 의 `auth.revocation-check.fail-closed-paths` 에 없다 — Redis 장애로 폐기 여부를 못 보면 통과(경고 · 지표만)한다. 읽기 전용이고, 폐기된 토큰으로 볼 수 있는 것도 상품 사실뿐이다.

## `GET /internal/draws/gift-products/{productId}` — 상품 삭제의 드로우 증정품 확인 (내부 전용)

**노출.** 위 두 API 와 같은 조건이다 — `/internal/**` 는 공개 라우팅에 두지 않고, OpenAPI 문서에서도 기본으로 빠진다.

**호출 주체.**

| 언제 | 누가 | 실린 JWT | 목적 |
| --- | --- | --- | --- |
| 관리자가 상품을 삭제할 때 한 번(캐시하지 않음) | catalog 상품 삭제 | 관리자(ADMIN) | 반영 전 드로우의 증정품이면 삭제를 409 로 막는다 |

**인증.** 호출자가 받은 관리자 토큰을 그대로 싣는다. order 는 **ADMIN 만** 받는다 — 회원 토큰은 403 `FORBIDDEN`(`@CurrentViewer` 로 가른다).

**요청.** 본문 없음. `productId` 는 `products.id`(UUID). UUID 가 아니면 400.

**응답 200.**

```json
{
  "success": true,
  "data": { "productId": "0199a3f2-8a10-7b21-9c32-4d5e6f708192", "drawInProgress": true },
  "error": null,
  "timestamp": "2026-10-16T06:00:00Z",
  "traceId": "…"
}
```

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `productId` | string(UUID) | 물은 상품 |
| `drawInProgress` | boolean | 그 상품을 증정품으로 둔 회차(`draw_campaigns.product_id`) 중 반영(`settled_at`) 전인 것이 있다 — 응모 전 · 응모 중 · 마감 뒤 추첨 · 반영 전. 마감 뒤에도 반영 전에는 증정품 재고를 잡고 있고 당첨 주문을 만들므로 진행 중으로 본다 |

**없는 상품도 200(false).** 상품의 유무는 catalog 가 안다 — order 는 회차만 본다. catalog 는 없는 상품이면 이 API 를 부르지 않고 404 로 끝낸다.

**겹침.** catalog 는 상품 행을 잠근 채 HTTP 를 기다리지 않으려고 이 확인을 삭제 트랜잭션 밖에서 먼저 한다. 드로우 생성(`AdminDrawService`)도 catalog 옵션 조회를 트랜잭션 밖에서 먼저 하고 INSERT 는 뒤에 하므로, 이 확인보다 먼저 옵션을 읽은 생성이 확인 뒤에 커밋되면 막지 못한다 — 둘 다 관리자 작업이다. 삭제가 커밋된 뒤에 옵션 조회를 시작한 드로우 생성은 옵션을 못 찾아(`GET /internal/options` 에서 빠짐) 404 다.

**멈춘 회차.** 반영(`settled_at`)을 적는 곳은 추첨 결과 반영 하나다. 반영이 계속 실패하는 회차가 있으면 그 증정품은 삭제가 계속 409 다 — 드로우 취소 경로는 없다.

**배포 순서.** order 를 catalog 보다 먼저 낸다. catalog 가 먼저 나가면 이 경로가 없어 공통 `NOT_FOUND`(연동 오류 500)가 되고, 그동안 상품 삭제가 전부 실패한다.

**오류.** 공통 봉투 그대로. catalog 는 200 이 아니면 전부 연동 오류(5xx 는 503 다시 시도, 그 밖은 500)로 다룬다 — 이 API 에 "대상 없음" 404 는 없다.

| 상태 | 코드 | 언제 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | `productId` 가 UUID 가 아님 |
| 401 | `UNAUTHENTICATED` | 토큰 없음 · 만료 · 서명 · 폐기된 토큰 |
| 403 | `FORBIDDEN` | ADMIN 이 아닌 토큰 |

## `GET /internal/stocks` — 옵션별 남은 수량 (내부 전용, 토큰 없음)

**노출.** 다른 `/internal/**` 와 같이 공개 라우팅에 두지 않고, OpenAPI 문서에서도 기본으로 빠진다. 이 API 는 토큰을 받지 않으므로 **공개 경로에 없다는 것이 유일한 차단**이다. 공개 ALB 가 order 로 보내는 경로는 모두 `/api/v1/` 아래다(nova-infra `terraform/application/order/service.yml` 의 `routes`, 10/17 기준 주문 · 장바구니 · 재고 관리 · 드로우). 그 목록에 `/internal` 을 넣으면 안 된다. 새어도 보이는 것은 상세 화면에 이미 공개되는 남은 수량뿐이다.

**호출 주체.**

| 언제 | 누가 | 실린 JWT | 목적 |
| --- | --- | --- | --- |
| 상품 목록 한 쪽을 고른 뒤 | catalog 목록 · 검색 | 없음(회원이면 있어도 된다) | 쪽에 실린 상품의 품절 칸 |
| 상품 상세를 볼 때 | catalog 상세 | 없음(회원이면 있어도 된다) | 옵션별 남은 수량 |
| 관리자가 증정품 후보를 볼 때 | catalog 관리자 | 관리자(있어도 된다) | 후보 옵션의 남은 수량 |

**인증.** 없다 — `SecurityConfig` 가 `GET /internal/stocks` 만 `/internal/**` 보다 앞에서 연다. 남은 수량은 회원 · 비회원 모두에게 상세 화면으로 이미 공개되는 값이고, 비회원 목록에는 실을 토큰이 없다. 토큰을 실어도 결과는 같고, 만료 · 폐기 · 위조 토큰도 401 이 아니라 익명으로 통과한다(`JwtAuthenticationFilter` 는 공개 경로에서 거부하지 않는다). 그래서 catalog 의 토큰 중계(`BearerTokenRelayInterceptor`)를 이 호출에서 꺼야만 하는 것은 아니다. 다만 끄는 편이 낫다 — 토큰을 실으면 유효한 토큰은 호출마다 order 의 폐기 조회(Redis)를 거치고, 만료된 토큰은 호출마다 WARN 로그(`auth rejected`)를 남긴다. 같은 경로의 다른 메서드와 하위 경로는 열지 않았다(401).

**요청.** 본문 없음. `optionIds` 에 옵션 id(`product_options.id`, UUID)를 쉼표로 잇거나(`optionIds=a,b`) 파라미터를 반복한다(`optionIds=a&optionIds=b`) — 둘은 같다. 규칙은 `GET /internal/options` 의 `ids` 와 같다.
- 같은 id 는 한 번으로 센다. 중복을 뺀 개수가 1~100 이어야 한다. 비었거나 · 없거나 · 101개 이상 · UUID 가 아니거나 · 빈 원소(`optionIds=a,` 의 끝 쉼표)가 있으면 400(`field=optionIds`).
- 상한 100 은 URL 길이로 정했다 — UUID 100개를 쉼표로 이으면 약 3.7KB, 파라미터를 반복하면(`optionIds=` 10자 + UUID 36자 + `&` = 47자 × 100) 약 4.7KB 로, 둘 다 Tomcat 요청 헤더 기본 상한(8KB) 안이다. catalog 의 HTTP 인터페이스(`StockClient`)는 반복 모양으로 보낸다. 더 많이 물어야 하면 catalog 가 100개씩 나눠 부른다.

**응답 200.** 봉투는 `common:web` 의 `ApiResponse` 그대로. 결과는 **요청 순서**(같은 id 는 처음 자리에 한 번)이고, **재고 행이 없는 옵션은 빠진다**.

```json
{
  "success": true,
  "data": {
    "items": [
      { "optionId": "0199a3f2-8a11-7c22-8d33-5e6f70819203", "available": 7 },
      { "optionId": "0199a3f2-8a11-7c22-8d33-5e6f70819204", "available": 0 }
    ]
  },
  "error": null,
  "timestamp": "2026-10-17T06:00:00Z",
  "traceId": "…"
}
```

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `optionId` | string(UUID) | 물은 옵션 |
| `available` | number | 지금 주문할 수 있는 수량 = `stock_total − stock_reserved − stock_sold`, 0 이상. 표의 CHECK(`ck_inventory_capacity`)가 음수를 막고, order 도 음수를 0 으로 잘라 낸다 |

**빠진 옵션 = 판매 불가.** 재고 행이 없는 옵션은 재고를 아직 넣지 않았거나 사전예약 옵션이다. catalog 의 지금 해석("행 없음 = 판매 불가")과 같다. order 는 옵션이 어느 상품 · 판매 방식인지 보지 않고, 없는 옵션 id 도 400 이 아니라 결과에서 빠질 뿐이다.

**읽기.** 트랜잭션 없이 잠그지 않는 PK 조회 한 문장으로 읽는다. 화면 표시용 값이라 그 사이 주문이 확보하면 바로 낡는다 — 주문 생성은 이 값과 상관없이 확보 UPDATE 로 다시 판정한다.

**배포 순서.** order 를 catalog 보다 먼저 낸다. 그 전의 order 에서는 이 경로가 `/internal/**` 규칙에 걸려, 토큰 없이 부르면 401 이고 토큰을 실으면 매핑이 없어 404 다.

**오류.** 공통 봉투 그대로.

| 상태 | 코드 | 언제 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | `optionIds` 가 비었거나 · 없거나 · 중복을 뺀 개수가 100 을 넘거나 · UUID 가 아니거나 · 빈 원소가 있음(`field=optionIds`) |
