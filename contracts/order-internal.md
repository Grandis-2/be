# order ↔ catalog 내부 API 계약

order 와 catalog 가 서로 부르는 내부 API 를 담는다(preorder 가 부르는 예약 취소 가능 여부는 여기 없다). 양쪽 구현의 기준이고, 문서와 어긋나는 쪽이 고친다.

- catalog → order: `GET /internal/order-items/{orderItemId}` — 리뷰 작성용 주문상품. order 가 응답을 만들고(`InternalOrderItemController` · `OrderQueryService.findItem`), catalog 의 리뷰 작성이 소비한다.
- order → catalog: `GET /internal/options` — 장바구니용 옵션 일괄 조회. catalog 가 응답을 만들고(`InternalOptionController` · `ProductOptionsQueryService.findOptions`), order 의 장바구니가 소비한다.

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
| `orderId` | string(UUID) | `orders.id` — 주문 **내부** id. 공개 주문 번호(`order_token`, 주문 API 의 `orderId`)가 아니다 |
| `productId` | string(UUID) | `order_items.product_id` |
| `optionId` | string(UUID) | `order_items.option_id` |
| `optionTitle` | string | 주문 당시 옵션명(`order_items.option_title_snapshot`). 상품이 바뀌어도 그대로다 |
| `orderStatus` | string | `orders.status` 이름 그대로(AWAITING_PAYMENT · AUTHORIZING · AWAITING_CONFIRMATION · PREPARING_ITEMS · READY_TO_SHIP · SHIPPED · DELIVERED · CANCELING · CANCELED) |
| `orderSource` | string | `orders.source` 이름 그대로(PREORDER · BUY_NOW · CART) |

**판정은 catalog 가 한다.** 배송 완료 여부, 사전예약 주문 제외, 주문상품 1건당 리뷰 1개는 catalog 가 가린다. order 는 판정하지 않고 사실만 돌려준다 — 배송 전 주문상품도 200 이다.

**현재 order 는 사전예약(PREORDER) 주문만 만든다.** BUY_NOW · CART 주문은 아직 생기지 않으므로, 지금은 모든 응답의 `orderSource` 가 `PREORDER` 이고 "사전예약 제외" 판정 때문에 리뷰 가능한 주문상품이 없다. 통합 시험에서 이를 버그로 오인하지 않는다.

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

## `GET /internal/options` — 장바구니용 옵션 일괄 조회 (내부 전용)

**노출.** 위 주문상품 API 와 같은 조건이다 — `/internal/**` 는 공개 라우팅에 두지 않고, OpenAPI 문서에서도 기본으로 빠진다. 이 엔드포인트는 USER 토큰으로 **비공개 · 판매 중지 상품까지** 돌려주므로 공개 경로 차단은 운영 필수 조건이다.

**호출 주체.**

| 언제 | 누가 | 실린 JWT | 목적 |
| --- | --- | --- | --- |
| 장바구니 조회 · 담기 · 장바구니 주문 생성 | order 장바구니 | 회원(USER) | 줄마다 표시할 상품 · 옵션 정보와 "살 수 있는가" 를 가릴 사실, 주문 스냅샷(가격 · 이름 · 보증) |

**인증.** 호출자가 받은 회원 토큰을 그대로 싣는다(`Authorization: Bearer {accessToken}`). catalog 는 `common:security` 필터로 검증하고 USER · ADMIN 을 받는다(`/internal/**` 규칙 — 그 밖의 역할은 403).

**요청.** 본문 없음. `ids` 에 옵션 id(`product_options.id`, UUID)를 쉼표로 잇거나(`ids=a,b`) 파라미터를 반복한다(`ids=a&ids=b`) — 둘은 같다.
- 같은 id 는 한 번으로 센다. 중복을 뺀 개수가 1~50 이어야 한다(장바구니 최대 줄 수 50). 비었거나 · 없거나 · 51개 이상 · UUID 가 아니거나 · 빈 원소(`ids=a,` 의 끝 쉼표)가 있으면 400(`field=ids`).

**응답 200.** 봉투는 `common:web` 의 `ApiResponse` 그대로. 결과는 **요청 순서**(같은 id 는 처음 자리에 한 번)이고, **없는 옵션은 빠진다** — 호출자는 빠진 id 를 "판매 종료" 로 판정한다.

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "optionId": "0199a3f2-8a11-7c22-8d33-5e6f70819203",
        "productId": "0199a3f2-8a10-7b21-9c32-4d5e6f708192",
        "productTitle": "아이폰 17",
        "optionTitle": "블랙 / 256GB",
        "sku": "BLK-256",
        "price": 1250000,
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
| `optionTitle` | string | 옵션 표시명(지금 값, 예: 블랙 / 256GB) |
| `sku` | string | 옵션 SKU |
| `price` | number | 옵션 최종가(정수 원) — 기본가 + 고른 값의 추가금 |
| `optionStatus` | string | 옵션 판매 상태(ACTIVE · PAUSED) |
| `saleMode` | string | 상품 판매 방식(IN_STOCK · PREORDER) |
| `productStatus` | string | 상품 판매 상태(ACTIVE · PAUSED) |
| `visible` | boolean | 상품 공개 여부. 늘 실린다 |
| `registrationCompleted` | boolean | 판매 방식별 준비 — 일반은 order 재고 행, 사전예약은 preorder 회차 행이 있다. 늘 실린다 |
| `warranty.offered` · `warranty.surcharge` | boolean · number | 상품의 보증 설정(옵션 문서의 `warranty`). 제공하지 않으면 `false` · `0` |
| `imageUrl` | string \| null | 상품 썸네일(첫 색상의 첫 장, 색상이 없으면 기본 사진의 첫 장). 없으면 null |

**판정은 order 가 한다.** 사전예약 제외 · 판매 중지 · 비공개 · 재고 · 보증 미제공 여부를 order 가 이 칸들로 가린다. catalog 는 비공개 · 판매 중지 · 사전예약 옵션도 200 으로 사실만 돌려준다.

**한 스냅샷.** 옵션 · 상품 · 노출 칸(`visible` · `productStatus` · `registrationCompleted`)은 한 트랜잭션(REPEATABLE READ)에서 읽는다 — 따로 읽으면 사이에 커밋된 판매 중지 · 공개 전환이 섞여 한순간도 없던 조합이 나온다.

**오류.** 공통 봉투 그대로.

| 상태 | 코드 | 언제 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | `ids` 가 비었거나 · 없거나 · 중복을 뺀 개수가 50 을 넘거나 · UUID 가 아니거나 · 빈 원소가 있음(`field=ids`) |
| 401 | `UNAUTHENTICATED` | 토큰 없음 · 만료 · 서명 · 폐기된 토큰 |
| 403 | `FORBIDDEN` | USER · ADMIN 이 아닌 토큰 |

**폐기 조회 실패.** 이 경로는 catalog 의 `auth.revocation-check.fail-closed-paths` 에 없다 — Redis 장애로 폐기 여부를 못 보면 통과(경고 · 지표만)한다. 읽기 전용이고, 폐기된 토큰으로 볼 수 있는 것도 상품 사실뿐이다.
