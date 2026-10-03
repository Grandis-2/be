-- orders.authorizing_provider_order_id — 주문이 지금 승인을 기다리는 결제창(결제사 주문 번호).
--
-- 왜: 결제창은 시도마다 새 번호를 받는다. 결제창 X 가 거절된 뒤 사용자가 새 결제창 Y 로 다시 승인하는 사이, 늦게 온 X 의 거절
--     (결과 이벤트 재전송 · 소비 재시도)이 "승인 중이면 거절을 반영" 전제를 통과하면 Y 진행 중에 주문이 결제 대기로 돌아가고,
--     Y 의 승인은 전제가 맞지 않아 반영되지 못한다(돈은 나갔는데 미결제). 거절 · 되돌림은 이 번호가 같을 때만 반영한다.
--     승인은 대조하지 않는다 — 대상당 성공 결제는 하나라(uq_payment_tx_active) 언제 와도 그 주문의 결제다.
-- 규칙: 승인 중(AUTHORIZING)일 때만 있다(ck_order_authorizing_attempt). 승인 요청 때 적고, 결과를 반영하며 지운다 —
--     주문 원장의 상태 변경 한 UPDATE 에서 함께 바뀐다.
-- 기존 행: 결제 승인이 배포된 적이 없어 AUTHORIZING 주문이 없다. 있으면 CHECK 에 걸려 ALTER 전체가 실패한다(조용히 틀리지 않는다).
-- 길이 · 콜레이션은 payment_transactions.provider_order_id 와 같다.
-- 배포: CHECK 추가는 기존 행을 검사하므로 ALGORITHM=COPY 다(표 복사 · 그동안 쓰기 막힘). 지금 orders 크기면 짧다 — 큰 표가 된
--     뒤라면 쓰기가 적은 때에 돌린다.

ALTER TABLE shop.orders
    ADD COLUMN authorizing_provider_order_id varchar(64) COLLATE utf8mb4_bin NULL AFTER status,
    ADD CONSTRAINT ck_order_authorizing_attempt
        CHECK ((status = 'AUTHORIZING') = (authorizing_provider_order_id IS NOT NULL));
