-- products.stock_ready_at 기존 상품 채우기(칸은 앞 마이그레이션이 만든다) — 이 마이그레이션이 도는 시점의 재고 행으로 정한다(계약 문서 "배포 순서" 3). order 가 이벤트를 보내기 시작한(NV-630) 뒤에
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
-- 다시 돌려도 결과가 같다(이미 채운 행은 건드리지 않는다) — 잠금 대기 초과 등으로 실패하면 flyway repair 뒤 그대로 다시 적용한다.
