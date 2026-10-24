-- 결제 결과를 끝내 모른 채 취소한 장바구니 · 바로 구매 주문의 결제창과, 그 결제창의 늦은 승인을 자동 환불하라고 요청한 시각.
--
-- 왜: 승인 중(AUTHORIZING) 주문은 결과가 올 때까지 기다렸다 — 결제사 장애가 길면 "결제 확인 중" 에 멈추고 재고도 확보에 묶인다.
--     만든 뒤 20분이 지나도 승인 중인 장바구니 · 바로 구매 주문은 취소하고 재고를 돌려준다(UnconfirmedCartOrderExpiry).
--     그 뒤 결제사가 승인했다고 알려 오면 돈이 나간 채 주문은 취소다 — 그 결제창의 승인일 때만 자동으로 환불한다.
--     취소하면 승인 중 결제창 칸(authorizing_provider_order_id)은 비워야 하므로(ck_order_authorizing_attempt) 따로 남긴다.
-- 규칙: unconfirmed_provider_order_id 는 그렇게 취소한 장바구니 · 바로 구매 주문에만 있다(ck_order_unconfirmed_attempt). 취소 UPDATE 하나에서
--     승인 중 결제창 칸의 값을 옮겨 적는다. auto_refund_requested_at 은 그 결제창의 늦은 승인을 받아 환불 요청을 아웃박스에 적은 때만
--     있다(ck_order_auto_refund) — 한 번만 적어 같은 승인을 다시 받아도 환불 요청은 하나다. 사전예약 주문은 결과를 끝까지 기다리고,
--     드로우 주문은 결제 기한이 없어 대상이 아니다.
-- 길이 · 콜레이션은 authorizing_provider_order_id(payment_transactions.provider_order_id)와 같다.
-- 기존 행: 두 칸 모두 비어 CHECK 를 지킨다. 칸을 더하기만 하고 구버전은 이 칸을 모르므로 같이 돌아도 된다 — 새 버전보다 먼저 적용한다.
-- 배포: ALGORITHM 을 지정하지 않는다 — CHECK 추가는 기존 행을 검사해야 해서 MySQL 이 COPY 를 고른다(표 복사 · 그동안 쓰기 막힘).
--     지금 orders 크기면 짧다.
--     orders 를 외래 키로 가리키는 표(order_items · order_events 등)에 열린 트랜잭션이 있으면 시작 전에 기다린다.

ALTER TABLE shop.orders
    ADD COLUMN unconfirmed_provider_order_id varchar(64) COLLATE utf8mb4_bin NULL AFTER authorizing_provider_order_id,
    ADD COLUMN auto_refund_requested_at datetime(6) NULL AFTER refund_failed_at,
    ADD CONSTRAINT ck_order_unconfirmed_attempt
        CHECK (unconfirmed_provider_order_id IS NULL OR (status = 'CANCELED' AND source IN ('CART', 'BUY_NOW'))),
    ADD CONSTRAINT ck_order_auto_refund
        CHECK (auto_refund_requested_at IS NULL OR unconfirmed_provider_order_id IS NOT NULL);
