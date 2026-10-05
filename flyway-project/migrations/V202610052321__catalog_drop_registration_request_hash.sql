-- 관리자 상품 등록의 멱등 처리에서 본문 해시를 뺀다.
-- 같은 Idempotency-Key 로 다시 오면 본문 내용은 대조하지 않고 첫 등록 결과를 돌려준다(형식 검사는 그대로 통과해야 한다).
-- 같은 키에 다른 본문이 오는 것은 프론트 버그일 때뿐이라 서버가 해시를 저장해 막지 않는다(2026-09-30 결정).
-- 이 칼럼을 쓰는 제약 · 인덱스는 없다.
ALTER TABLE shop.product_registrations DROP COLUMN request_hash;
