-- 예약의 순번 · 배송 차수를 NULL 허용으로 바꾼다.
--
-- 왜: 접수는 예약 · 이력 · 작업 · 아웃박스를 먼저 넣고 맨 끝에 회차 행을 잠가 순번을 올린 뒤 그 행에 순번 · 차수를 채운다 — 회차 잠금을 쥐는
--     문장을 줄여 같은 상품 접수의 처리량을 올린다. 넣는 순간에는 순번 · 차수가 없어 두 칸이 비어 있어야 한다.
-- 규칙: 커밋된 예약은 늘 두 칸이 차 있다 — 채우는 UPDATE 와 넣는 INSERT 가 한 트랜잭션이고, 빈 행은 커밋 전에 다른 트랜잭션에 보이지 않는다.
--     uq_preorder_position 은 NULL 끼리 겹쳐도 되고, ck_preorder_position_positive 는 NULL 을 통과시키며,
--     fk_preorder_batch 는 NULL 을 검사하지 않는다.
-- 기존 행: 모두 값이 있어 그대로다. 구버전은 늘 값을 넣으므로 같이 돌아도 된다 — 새 버전보다 먼저 적용한다.
-- 배포: ALGORITHM=COPY, LOCK=SHARED — ck_preorder_payable_from · ck_preorder_reserved_at 이 있어 MySQL 8.4 가 INPLACE 를 거부한다.
--     표를 다시 쓰는 동안 읽기는 되고 쓰기는 막힌다(20만 행에 약 5초). 사전예약이 열려 있지 않을 때 돌린다.

ALTER TABLE shop.preorders
    MODIFY shipment_batch_id BINARY(16) NULL,
    MODIFY queue_position    BIGINT     NULL,
    ALGORITHM = COPY, LOCK = SHARED;
