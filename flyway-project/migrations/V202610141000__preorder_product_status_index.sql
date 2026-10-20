-- preorders 에 (product_id, status, payable_from) 색인을 더한다.
--
-- 왜: 관리자 운영 현황이 상품 하나의 상태별 예약 수 · 결제 기한이 지난 수를 센다. 지금 색인은 상품 id 로 시작하는 것에
--     상태가 없어(ix_preorder_option · ix_preorder_batch) 그 상품의 예약 행을 모두 읽어야 한다. 이 색인이면 색인만 읽고 센다.
--     관리자 예약 목록을 상품 + 상태 · 결제 단계로 거를 때도 같은 색인을 탄다.
-- 배포: 색인만 더한다. 구버전은 영향이 없으므로 서비스 배포 전에 적용한다.

ALTER TABLE shop.preorders
    ADD KEY ix_preorder_product_status (product_id, status, payable_from);
