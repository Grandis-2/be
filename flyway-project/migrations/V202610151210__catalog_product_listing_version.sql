-- products.listing_version — 상품명 · 판매 상태 · 공개 여부의 변경 번호(catalog 소유).
--
-- 왜: preorder 가 이 셋을 회차 행에 사본으로 둔다(오픈 알림의 문구 · 대상 판정 — catalog 표는 읽지 않는다). catalog 는 바뀔 때마다
--     PREORDER_PRODUCT_CHANGED 에 지금 값과 이 번호를 싣고, 표준 큐라 순서가 뒤집혀 오므로 preorder 는 번호가 더 클 때만 덮는다.
-- 어떻게: 상품 행을 잠근 수정 트랜잭션에서 셋 중 하나가 실제로 바뀔 때만 +1 한다. 새 상품은 1 로 만든다 — preorder 의 0 은
--     "catalog 값을 받기 전" 이라 등록 이벤트가 이겨야 한다.
-- 값: 기존 행은 0. 사본은 preorder 마이그레이션(V202610151211)이 이 표에서 한 번 채우고, 다음 변경(1 이상)부터 이벤트로 따라간다.
-- 배포: 칸만 더하고 기본값이 있어 구버전은 영향이 없다. 서비스 배포 전에 적용한다.

ALTER TABLE shop.products
    ADD COLUMN listing_version bigint NOT NULL DEFAULT 0 AFTER visible;
