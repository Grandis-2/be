-- order_items.model_name_snapshot: 주문 때 상품 모델명(catalog products.model_name, 최대 40자). 장바구니 · 바로 구매 · 드로우 당첨 주문이
-- 싣는다. 사전예약 주문과 이 칸 전에 만든 주문은 NULL 이다 — 옛 주문을 지금 값으로 채우면 주문 때 값이 아니게 된다.
-- draw_campaigns.model_name_snapshot: 회차를 만들 때 증정품 상품의 모델명. 당첨 주문이 이 값을 옮긴다. 이 칸 전에 만든 회차는 NULL.
-- 새 칸만 더하므로 새 버전보다 먼저 적용해도 된다.
ALTER TABLE shop.order_items
    ADD COLUMN model_name_snapshot varchar(40) NULL AFTER product_title_snapshot;

ALTER TABLE shop.draw_campaigns
    ADD COLUMN model_name_snapshot varchar(40) NULL AFTER product_title_snapshot;
