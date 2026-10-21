-- 결제된 사전예약 주문의 취소 시도 순번과, 그 취소 결과를 preorder 에 알린 시각을 주문에 적는다.
--
-- 왜: 결제된 사전예약을 취소하면 주문은 환불이 끝날 때까지 취소 중이고, 예약 취소 요청(PREORDER_CANCEL_REQUESTED)은 그동안 늦춰
--     다시 받는다. 환불이 끝나도 order 는 알리지 않아 preorder 는 다음 재수신(최대 15분 간격)에야 결과를 받았다.
--     환불을 마친 트랜잭션에서 바로 알리려면 결과에 실을 순번(preorder 가 지금 시도인지 가르는 값)이 주문에 있어야 하고,
--     늦춰 둔 요청이 다시 와도 결과를 또 보내지 않으려면 이미 알렸다는 표식이 있어야 한다.
-- 값: preorder_cancel_sequence 는 주문을 취소 중으로 바꾼 요청의 cancelSequence. 사전예약 주문의 취소 중 · 취소에만 있다(CHECK).
--     미결제 취소(바로 취소)는 결과를 그 트랜잭션에서 보내므로 적지 않는다.
--     preorder_cancel_reported_at 은 환불을 마치며 그 순번으로 결과를 아웃박스에 적은 시각. 순번이 있는 취소된 주문에만 있다(CHECK).
--
-- 구버전과 같이 돌아도 된다 — 새 버전보다 먼저 적용한다. 두 칸은 NULL 을 허용하고 구버전은 쓰지 않는다. 구버전이 하는 전이
-- (취소 중 → 취소)는 두 CHECK 를 그대로 만족한다. 채우기는 없다: 지금 취소 중인 주문과 구버전이 환불을 마친 주문은 표식이 없어,
-- 그 결과는 지금처럼 늦춰 다시 받은 요청이 적는다. 배포 중 구버전이 다시 받은 요청은 표식을 모르므로 결과가 한 번 더 갈 수 있다.
-- 배포: CHECK 추가는 기존 행을 검사하므로 ALGORITHM=COPY 다(표 복사 · 그동안 쓰기 막힘). 지금 orders 크기면 짧다.
--     orders 를 외래 키로 가리키는 표(order_items · order_events 등)에 열린 트랜잭션이 있으면 시작 전에 기다린다.

ALTER TABLE shop.orders
    ADD COLUMN preorder_cancel_sequence bigint NULL AFTER refund_failed_at,
    ADD COLUMN preorder_cancel_reported_at datetime(6) NULL AFTER preorder_cancel_sequence,
    ADD CONSTRAINT ck_order_preorder_cancel_sequence
        CHECK (preorder_cancel_sequence IS NULL OR (source = 'PREORDER' AND status IN ('CANCELING', 'CANCELED'))),
    ADD CONSTRAINT ck_order_preorder_cancel_reported
        CHECK (preorder_cancel_reported_at IS NULL OR (preorder_cancel_sequence IS NOT NULL AND status = 'CANCELED'));
