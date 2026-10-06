-- preorders: 예약 확정(RESERVED) 상태와 결제 사건 시각 두 칸.
--
-- 왜: 결제가 끝나도 예약이 PAYABLE 로 남아 "예약 확정" 을 보일 수 없고, 결제된 예약도 만료 후보였다.
--     결제 진행 단계(승인 중 · 실패 · 환불)는 order 만 갖는다 — 예약은 결제가 시작 · 확인됐다는 사건과 그 시각만 남긴다.
-- 값:
--   payment_started_at  order 가 사전예약 주문을 만든 시각(화면의 "결제 진행 중" 표시용, 판단에 쓰지 않음). 처음 한 번만 쓴다.
--   reserved_at         결제 확인 시각. 처음 한 번만 쓰고 취소 뒤에도 남긴다 — 취소가 거절되면 이 값으로 RESERVED 로 돌아간다.
-- active_marker 는 그대로다(RESERVED 도 활성이라 같은 모델 재신청을 막는다).

ALTER TABLE shop.preorders
    ADD COLUMN payment_started_at datetime(6) NULL AFTER payable_from,
    ADD COLUMN reserved_at        datetime(6) NULL AFTER payment_started_at,
    DROP CHECK ck_preorder_status;

ALTER TABLE shop.preorders
    ADD CONSTRAINT ck_preorder_status
        CHECK (status IN ('PENDING_SYNC', 'PAYABLE', 'RESERVED', 'CANCELING', 'CANCELED')),
    ADD CONSTRAINT ck_preorder_reserved_at CHECK (status <> 'RESERVED' OR reserved_at IS NOT NULL);
