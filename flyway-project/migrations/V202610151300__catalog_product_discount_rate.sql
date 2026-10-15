-- 상품 할인율(정수 %, 0~99). 일반 · 사전예약 상품 모두 받고, 등록 때 비우면 0 이다.
--
-- 옵션의 price 는 그대로 정가(기본가 + 고른 값의 추가금)이고, 할인가는 읽을 때 계산한다 — 정가 × (100 − 할인율) / 100 을 100원 단위로 버린다.
-- 할인율이 0 이면 정가 그대로다(100원 미만도 버리지 않는다). 보증 추가금은 할인하지 않는다.
-- 예약 · 주문은 접수 시점의 할인가를 복사하므로 할인율을 고쳐도 과거 거래에 소급되지 않는다.
--
-- 다른 모듈의 시험 픽스처가 products 를 이 칸 없이 INSERT 하므로 DEFAULT 를 둔다. 기존 행은 0(할인 없음)으로 채워져 가격이 그대로다.
ALTER TABLE shop.products
    ADD COLUMN discount_rate int NOT NULL DEFAULT 0 AFTER base_price,
    ADD CONSTRAINT ck_product_discount_rate CHECK (discount_rate BETWEEN 0 AND 99);
