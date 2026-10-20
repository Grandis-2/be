-- products.stock_ready_at: 일반 판매 상품의 판매 준비 — order 가 그 상품 옵션의 재고 행을 만들었다는 이벤트(IN_STOCK_PRODUCT_READY,
-- contracts/order-catalog-events.md)를 처음 받은 시각(기존 상품은 다음 마이그레이션의 채우기가 그때 시각으로 채운다). 목록 · 검색 · 노출 판정이 order 의 재고 표를 직접 읽지 않고 이 칸을 본다.
-- 준비는 되돌아가지 않는다(재고 행을 지우는 길이 없다). 사전예약 상품은 NULL 이다(준비는 preorder 회차가 정한다).
ALTER TABLE shop.products
    ADD COLUMN stock_ready_at datetime(6) NULL AFTER status;
-- 칸 추가(DDL)와 채우기(UPDATE)를 나눈다 — MySQL 의 DDL 은 바로 커밋되므로 한 파일에 두면 채우기가 실패했을 때 칸만 남고, 다시 돌리면
-- Duplicate column 으로 막힌다.
