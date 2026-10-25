-- 결과를 모른 채 취소한 장바구니 · 바로 구매 주문의 자동 환불이 끝난 시각.
--
-- 왜: 그 주문은 이미 취소라 환불이 끝나도 상태가 바뀌지 않는다 — 완료를 남길 곳이 없어 "요청했는데 아직 안 끝난 자동 환불"을 관리자 목록에서
--     가릴 수 없고, 확정 실패 뒤 관리자가 다시 보내 환불이 끝나도 화면의 환불 실패 표시가 남는다. 완료 시각을 남기고 같은 트랜잭션에서 이력
--     (REFUND_COMPLETED, 취소 → 취소)을 한 줄 적는다.
-- 규칙: 자동 환불을 요청한 주문에만 있다(ck_order_auto_refund_completed). 한 번만 적는다 — 같은 결과를 다시 받아도 이력은 하나다.
-- 기존 행: 칸이 비어 CHECK 를 지킨다. 칸을 더하기만 하고 구버전은 이 칸을 모르므로 같이 돌아도 된다 — 새 버전보다 먼저 적용한다.
--     완료 시각을 채울 기존 행은 없다는 전제다 — 늦은 승인 자동 환불(NV-818)은 nova.order.payment-unconfirmed.enabled 를 끈 채 배포되고
--     스위치는 이 마이그레이션 · 서비스 배포 뒤에 켠다. 그 전에 켜면 이 칸 없이 끝난 자동 환불이 관리자 목록에 "끝나지 않음"으로 남는다.
-- 배포: ALGORITHM 을 지정하지 않는다 — CHECK 추가는 기존 행을 검사해야 해서 MySQL 이 COPY 를 고른다(표 복사 · 그동안 쓰기 막힘).
--     지금 orders 크기면 짧다.

ALTER TABLE shop.orders
    ADD COLUMN auto_refund_completed_at datetime(6) NULL AFTER auto_refund_requested_at,
    ADD CONSTRAINT ck_order_auto_refund_completed
        CHECK (auto_refund_completed_at IS NULL OR auto_refund_requested_at IS NOT NULL);
