-- orders.paid_at — 주문이 결제 승인을 반영한 때의 결제 시각.
--
-- 왜: 사전예약 주문의 결제 시각을 preorder 에 알린다 — 승인 알림(PREORDER_PAYMENT_CONFIRMED.paidAt)과 예약 취소 거절
--     (PREORDER_ORDER_SETTLED.paidAt, 결제된 주문이면 사유와 상관없이). 거절은 승인 뒤 한참 지나 오므로 order 가 그 시각을 갖고 있어야 한다.
--     승인 시각의 원본은 payment 의 payments.approved_at 이지만 payment 소유라 order 가 읽지 않는다(모듈 경계).
-- 값: 결과 이벤트로 승인을 반영하면 그 이벤트의 approvedAt(결제사 승인 시각), 승인 API 응답으로 반영하면 반영한 시각이다 — 응답에는 승인
--     시각이 없다. 대개 결제사 승인 직후라 몇 초 차이지만, 결과를 모르던 승인을 사용자의 재요청이 회수하면 그 재요청 시각이라 더 늦을 수 있다.
--     승인을 반영할 때 한 번 적고 바꾸지 않는다. 환불로 취소돼도 기록으로 남는다. 드로우 당첨 주문은 결제를 거치지 않아(응모비로 냈다) 비어 있다.
-- 규칙: 결제 전 상태(결제 대기 · 승인 중)에는 없다(ck_order_paid). 결제된 상태에 늘 있다고는 걸지 않는다 — 드로우 당첨 주문, 그리고
--     이 칸이 생긴 뒤 새 버전으로 바뀌기 전에 구버전이 반영한 승인이 비어 있다. 읽는 쪽(OrderReader.findPaidAt)은 비어 있으면 승인 반영 이력의
--     시각을 쓴다 — 기존 행 채우기(V202610161601)와 같은 규칙이다.
--
-- 칸을 더하기만 하고 구버전은 이 칸을 모르므로 같이 돌아도 된다 — 새 버전보다 먼저 적용한다.
-- 채우기는 다음 파일에 따로 둔다 — MySQL DDL 은 되돌리지 않아, 한 파일에서 채우기가 실패하면 칸만 생긴 채 멈춰 다시 돌릴 수 없다.
-- 배포: CHECK 추가는 기존 행을 검사하므로 ALGORITHM=COPY 다(표 복사 · 그동안 쓰기 막힘). 지금 orders 크기면 짧다.
--     orders 를 외래 키로 가리키는 표(order_items · order_events 등)에 열린 트랜잭션이 있으면 시작 전에 기다린다.

ALTER TABLE shop.orders
    ADD COLUMN paid_at datetime(6) NULL AFTER stock_released_at,
    ADD CONSTRAINT ck_order_paid CHECK (paid_at IS NULL OR status NOT IN ('AWAITING_PAYMENT', 'AUTHORIZING'));
