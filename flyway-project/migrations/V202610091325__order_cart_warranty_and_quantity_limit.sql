-- 장바구니 · 주문상품에 보증(AppleCare 등) 선택을 둔다 — 보증은 구매 때 옵션처럼 더해 고르는 것이다(catalog 의 products.options.warranty).
--
-- cart_items: 같은 옵션이라도 보증 포함 · 미포함이면 다른 줄이다. warranty_selected 를 더하고 유일 키를 (회원, 옵션, 보증)으로 넓힌다.
--   한 줄 수량은 1~99(장바구니 상한). 줄 수 50 은 앱이 지킨다(담기 트랜잭션).
--   옛 유일 키 (customer_id, option_id) 는 fk_cart_customer 의 인덱스이기도 해서 혼자 지우면 1553 이다 — 새 키(customer_id 로 시작)를
--   같은 문장에서 먼저 더해 외래키 인덱스를 넘겨받게 한다(MySQL 8.4.11 실측).
-- order_items: 같은 옵션은 한 줄이다 — 장바구니의 보증 포함 · 미포함 두 줄은 주문 때 합치고, 그중 보증을 산 수량과 보증가를 둔다.
--   줄 금액 = unit_price_snapshot × quantity + warranty_price_snapshot × warranty_quantity. 유일 키 uq_order_item_option(order_id, option_id) 는 그대로다
--   (주문상품 하나에 리뷰 하나 — 같은 주문 · 같은 상품(옵션)의 리뷰가 한 번이 된다. 같은 상품의 다른 옵션은 다른 줄이라 리뷰도 따로다).
--
-- 기본값이 있어 기존 행 · 기존 칼럼만 쓰는 INSERT(사전예약 주문 · 다른 모듈 시험 픽스처)는 그대로 들어간다. 구버전은 새 칸을 모르고
-- 장바구니를 쓰지 않으므로 이 마이그레이션은 새 버전보다 먼저 적용해도 된다.
ALTER TABLE shop.cart_items
    ADD COLUMN warranty_selected tinyint(1) NOT NULL DEFAULT 0 AFTER option_id,
    ADD UNIQUE KEY uq_cart_customer_option_warranty (customer_id, option_id, warranty_selected),
    DROP INDEX uq_cart_customer_option,
    DROP CHECK ck_cart_quantity,
    ADD CONSTRAINT ck_cart_quantity CHECK (quantity BETWEEN 1 AND 99);

ALTER TABLE shop.order_items
    ADD COLUMN warranty_quantity       int           NOT NULL DEFAULT 0 AFTER quantity,
    ADD COLUMN warranty_price_snapshot decimal(12,0) NOT NULL DEFAULT 0 AFTER unit_price_snapshot,
    ADD CONSTRAINT ck_order_item_warranty_quantity CHECK (warranty_quantity BETWEEN 0 AND quantity),
    ADD CONSTRAINT ck_order_item_warranty_price CHECK (warranty_price_snapshot >= 0);
