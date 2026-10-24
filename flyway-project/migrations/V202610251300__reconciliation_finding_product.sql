-- 정합성 검사 결과에 상품 ------------------------------------------
-- 왜: 관리자 화면이 불일치를 상품으로 거르고 상품마다 건수를 본다. 상품은 be_snapshot JSON 안에만 있어 거를 수 없었다.
-- product_id: 불일치 대상의 사전예약 상품. 예약 · Mock 건은 그 예약의 상품, 순번 빈틈은 그 상품, 이벤트는 대상의 상품.
--     상품을 알 수 없는 건(등록 전 취소 표식만 있는 Mock 키 등)은 비어 있다. 이 칸이 생기기 전 결과도 비어 있다
-- 색인: 실행의 상품별 목록 · 건수를 (run_id, product_id) 로 읽는다
-- 배포: 빈 칸 하나와 색인만 더하므로 서비스 배포 전에 적용한다. 앞 batch 는 이 칸을 모르고 넣어도 비어 있을 뿐이다
ALTER TABLE shop.reconciliation_findings
    ADD COLUMN product_id binary(16) NULL AFTER target_key,
    ADD KEY ix_reconciliation_finding_product (run_id, product_id);
