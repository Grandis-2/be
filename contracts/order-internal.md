# order ↔ catalog 내부 API 계약

**catalog 가 부르는 order 내부 API 만** 담는다(preorder 가 부르는 예약 취소 가능 여부는 여기 없다). 양쪽 구현의 기준이다. order 가 응답을 만들고(`InternalOrderItemController` · `OrderQueryService.findItem`), catalog 의 리뷰 작성이 소비한다. 문서와 어긋나는 쪽이 고친다.

## `GET /internal/order-items/{orderItemId}` — 리뷰 작성용 주문상품 (내부 전용)

**노출.** `/internal/**` 는 공개 라우팅(ALB)에 두지 않는다 — 인프라에서 병행하고 운영 활성화 전에 차단을 확인한다(preorder-internal.md 와 같은 조건). 이 엔드포인트는 USER 토큰과 주인 확인을 요구해 공개 경로로 새어도 본인 주문상품만 보이지만, 내부 전용 계약이므로 공개하지 않는다. OpenAPI 문서에서도 기본으로 빠진다(`nova.openapi.include-internal`).

**호출 주체.**

| 언제 | 누가 | 실린 JWT | 목적 |
| --- | --- | --- | --- |
| 회원이 리뷰를 쓸 때 한 번(캐시하지 않음) | catalog 리뷰 작성 | 회원(USER) | "이 회원의 주문상품인가, 배송이 끝났는가, 어떤 주문 출처인가" 를 판정할 사실 |

**인증.** 호출자가 받은 회원 토큰을 그대로 싣는다(`Authorization: Bearer {accessToken}`). order 는 `common:security` 필터로 검증하고 **USER 만** 받는다 — ADMIN 토큰은 회원 id 가 없어 403 이다(`@CurrentCustomerId`).

**요청.** 본문 없음. `orderItemId` 는 `order_items.id`(숫자). 숫자가 아니면 400.

**응답 200.** 봉투는 `common:web` 의 `ApiResponse` 그대로.

```json
{
  "success": true,
  "data": {
    "orderItemId": 501,
    "orderId": 77,
    "productId": 12,
    "optionId": 101,
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
| `orderItemId` | number | `order_items.id` |
| `orderId` | number | `orders.id` — 주문 **내부** id. 공개 주문 번호(`order_token`, 주문 API 의 `orderId`)가 아니다 |
| `productId` | number | `order_items.product_id` |
| `optionId` | number | `order_items.option_id` |
| `optionTitle` | string | 주문 당시 옵션명(`order_items.option_title_snapshot`). 상품이 바뀌어도 그대로다 |
| `orderStatus` | string | `orders.status` 이름 그대로(AWAITING_PAYMENT · AUTHORIZING · AWAITING_CONFIRMATION · PREPARING_ITEMS · READY_TO_SHIP · SHIPPED · DELIVERED · CANCELING · CANCELED) |
| `orderSource` | string | `orders.source` 이름 그대로(PREORDER · BUY_NOW · CART) |

**판정은 catalog 가 한다.** 배송 완료 여부, 사전예약 주문 제외, 주문상품 1건당 리뷰 1개는 catalog 가 가린다. order 는 판정하지 않고 사실만 돌려준다 — 배송 전 주문상품도 200 이다.

**현재 order 는 사전예약(PREORDER) 주문만 만든다.** BUY_NOW · CART 주문은 아직 생기지 않으므로, 지금은 모든 응답의 `orderSource` 가 `PREORDER` 이고 "사전예약 제외" 판정 때문에 리뷰 가능한 주문상품이 없다. 통합 시험에서 이를 버그로 오인하지 않는다.

**오류.** 공통 봉투 그대로.

| 상태 | 코드 | 언제 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | `orderItemId` 가 숫자가 아님 |
| 401 | `UNAUTHENTICATED` | 토큰 없음 · 헤더 모양 이상 · 만료 · 서명 · `aud` 불일치 · 폐기된 토큰 |
| 403 | `FORBIDDEN` | USER 가 아닌 토큰(관리자) |
| 404 | `ORDER_ITEM_NOT_FOUND` | 없는 주문상품 · **남의 주문상품** — 존재를 드러내지 않으려고 같은 코드로 답한다 |

**404 는 코드로 가른다.** catalog 는 `error.code` 가 `ORDER_ITEM_NOT_FOUND` 일 때만 "구매 내역 없음"으로 판정한다. 그 밖의 404(공통 `NOT_FOUND` — 경로 · 배포 불일치)와 403(경로가 `/internal/**` 밖으로 어긋나면 `FORBIDDEN`)은 연동 오류로 5xx 와 같게 다룬다 — catalog 는 늘 USER 토큰을 실으므로 403 이 정상 업무 응답일 일이 없다. 404 를 모두 "없음"으로 읽으면 경로 하나가 어긋났을 때 모든 리뷰 작성이 조용히 거절되고 장애로 드러나지 않는다.

**폐기 조회 실패.** 이 경로는 order 의 `auth.revocation-check.fail-closed-paths` 에 없다 — Redis 장애로 폐기 여부를 못 보면 통과(경고 · 지표만)하고 retryable 401 은 나오지 않는다. 폐기된 토큰으로 볼 수 있는 것도 본인 주문상품의 사실뿐이다.

5xx 나 응답 지연은 catalog 가 회원에게 "잠시 후 다시" 로 답한다(catalog 쪽 읽기 1초).
