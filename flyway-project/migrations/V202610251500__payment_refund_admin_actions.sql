-- payment_refund_admin_actions — 관리자가 멈춘 환불에 한 조치(payment 소유).
--
-- 왜: 확정 실패 · 에스컬레이션된 환불은 자동으로 다시 열리지 않는다(결정 33) — 돈이 돌아가지 않은 채 사람이 풀어야 한다. 관리자가 환불을
--     다시 보내거나(결제사가 아직 한 푼도 취소하지 않았을 때만) 결제사에서 직접 환불한 사실을 기록하면(결제사 조회로 전액 취소가 확인될 때만)
--     거래 행이 바뀐다. 누가 · 언제 · 왜 · 그때 결제사 상태가 어땠는지를 거래 행이 아니라 이 표에 남긴다 — 한 대상에 조치가 여러 번일 수
--     있고, 거래 행에 칸을 늘리면 앞선 조치가 덮인다.
-- 규칙: 받아들인 조치만 남긴다(거절된 시도는 아무것도 바꾸지 않아 로그만). 행을 지우거나 고치지 않는다. 결제 키는 싣지 않는다.
--     action — RETRY(확정 실패한 환불을 새 거래로 다시 연다) · RETRY_AFTER_ESCALATION(결과를 끝내 몰라 멈춘 환불을 닫고 새 거래로 다시 연다) ·
--     MANUAL_COMPLETE(결제사에서 직접 환불한 것을 기록한다). previous_transaction_id 는 조치 전 마지막 환불, transaction_id 는 조치로
--     환불 완료 · 재전송을 맡은 거래다.
-- 새 표만 더하므로 서비스 배포 전에 적용한다.
CREATE TABLE shop.payment_refund_admin_actions (
    id                      binary(16)    NOT NULL,
    target_type             varchar(20)   NOT NULL,
    target_id               binary(16)    NOT NULL,
    action                  varchar(30)   NOT NULL,
    previous_transaction_id binary(16)    NOT NULL,
    transaction_id          binary(16)    NOT NULL,
    -- 조치한 관리자(order 가 실어 보낸 관리자 토큰의 주체).
    requested_by            varchar(64)   NOT NULL,
    note                    varchar(500)  NOT NULL,
    -- 조치 전 결제사 조회 결과. 결제사 상태 이름 · 처음 금액 · 잔액.
    provider_status         varchar(30)   NOT NULL,
    provider_total_amount   decimal(12,0) NOT NULL,
    provider_balance_amount decimal(12,0) NOT NULL,
    created_at              datetime(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY ix_payment_refund_admin_target (target_type, target_id, created_at),
    CONSTRAINT ck_payment_refund_admin_target_type CHECK (target_type IN ('ORDER','DRAW_ENTRY')),
    CONSTRAINT ck_payment_refund_admin_action
        CHECK (action IN ('RETRY','RETRY_AFTER_ESCALATION','MANUAL_COMPLETE')),
    CONSTRAINT ck_payment_refund_admin_amounts
        CHECK (provider_total_amount >= 0 AND provider_balance_amount >= 0)
) ENGINE = InnoDB;
