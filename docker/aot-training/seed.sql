-- AOT 학습 시드. 쓰기 API 가 없는 것만 넣는다(카테고리 · 회원). 상품 · 회차 · 재고 · 예약은 학습 중 API 와 이벤트로 만든다.
-- train.sh 는 여기서 넣은 카테고리 · 회원을 DB 에서 읽어 쓴다(회원마다 토큰을 만든다).
INSERT INTO categories (id, name, sort_order, created_at, updated_at)
VALUES (UUID_TO_BIN('01990000-0000-7000-8000-000000000001'), '모바일', 0, NOW(6), NOW(6));

INSERT INTO customers (id, kakao_id, display_name, created_at, updated_at)
WITH RECURSIVE n AS (SELECT 1 AS i UNION ALL SELECT i + 1 FROM n WHERE i < 30)
SELECT UUID_TO_BIN(CONCAT('00000000-0000-7000-8000-', LPAD(i, 12, '0'))), CONCAT('aot-', i), CONCAT('학습회원', i), NOW(6), NOW(6)
FROM n;
