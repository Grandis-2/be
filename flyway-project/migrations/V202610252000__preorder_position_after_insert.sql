-- 접수는 예약을 순번 · 차수 없이 먼저 넣고 같은 트랜잭션에서 채운다. 커밋된 행은 늘 두 칸이 차 있다.
-- COPY 인 까닭: ck_preorder_payable_from · ck_preorder_reserved_at 이 있으면 MySQL 8.4 가 INPLACE 를 거부한다.

ALTER TABLE shop.preorders
    MODIFY shipment_batch_id BINARY(16) NULL,
    MODIFY queue_position    BIGINT     NULL,
    ALGORITHM = COPY, LOCK = SHARED;
