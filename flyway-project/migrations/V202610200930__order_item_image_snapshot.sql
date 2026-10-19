-- order_items.image_url_snapshot: 주문 때 그 옵션의 사진(catalog 내부 옵션 조회의 imageUrl — 고른 색상의 첫 장, 없으면 상품 썸네일, 최대 1000자).
-- 장바구니 · 바로 구매 주문은 주문 때 catalog 값, 드로우 당첨 주문은 회차의 사진 스냅샷(draw_campaigns.image_url_snapshot)을 싣는다.
-- 사전예약 주문, 이 칸 전에 만든 주문, 이 마이그레이션 뒤 새 버전 배포 전에 옛 앱이 만든 주문은 NULL 이다 — 옛 주문을 지금 사진으로 채우면
-- 주문 때 사진이 아니게 된다. 사진 정리 배치는 이 칸이 가리키는 객체를 지우지 않는다(REVIEW.md 사진 정리).
-- 새 칸만 더하므로 새 버전보다 먼저 적용해도 된다.
ALTER TABLE shop.order_items
    ADD COLUMN image_url_snapshot varchar(1000) NULL AFTER option_title_snapshot;
