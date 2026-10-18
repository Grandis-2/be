-- products.stock_ready_at: 일반 판매 상품의 판매 준비 — order 가 그 상품 옵션의 재고 행을 만들었다는 이벤트(IN_STOCK_PRODUCT_READY,
-- contracts/order-catalog-events.md)를 처음 받은 시각(아래 채우기로 채운 행은 이 마이그레이션이 돈 시각). 목록 · 검색 · 노출 판정이 order 의 재고 표를 직접 읽지 않고 이 칸을 본다.
-- 준비는 되돌아가지 않는다(재고 행을 지우는 길이 없다). 사전예약 상품은 NULL 이다(준비는 preorder 회차가 정한다).
ALTER TABLE shop.products
    ADD COLUMN stock_ready_at datetime(6) NULL AFTER status;

-- 기존 상품 채우기 — 이 마이그레이션이 도는 시점의 재고 행으로 정한다(계약 문서 "배포 순서" 3). order 가 이벤트를 보내기 시작한(NV-630) 뒤에
-- 돌아야 그 사이 만든 재고 행이 빠지지 않는다. 그 뒤 새 이미지의 소비기가 뜰 때까지(배포가 끝날 때까지)의 이벤트는 큐에 남았다가 소비된다
-- (큐 보관 4일).
UPDATE shop.products p
   SET p.stock_ready_at = UTC_TIMESTAMP(6)
 WHERE p.sale_mode = 'IN_STOCK'
   AND p.stock_ready_at IS NULL
   AND EXISTS (SELECT 1
                 FROM shop.product_options o
                 JOIN shop.option_inventories i ON i.option_id = o.id
                WHERE o.product_id = p.id);
