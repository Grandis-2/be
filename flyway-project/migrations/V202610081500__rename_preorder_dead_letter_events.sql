-- dead_letter_events → preorder_dead_letter_events. 서비스 표는 이름에 서비스가 드러난다(preorder_outbox_events 와 짝).
--
-- 왜: preorder-events 의 DLQ 만 쌓는 preorder 전용 표다(예약 외래 키 · 회원 id). 이름만 공용처럼 보였다.
-- 어떻게: 운영 배포 전이라 새 표 + 행 이동 대신 이름만 바꾼다. 인덱스 · 외래 키 · CHECK 이름도 표에 맞춘다.
--     외래 키 · CHECK 는 이름을 바꿀 수 없어 지우고 같은 정의로 다시 건다. 외래 키가 만든 인덱스도 이름을 붙인다.
--     자기 참조 외래 키는 옛 이름으로 건다 — 같은 문장 안에서는 새 이름의 표가 아직 없고, 이름 변경이 참조를 따라 바꾼다.
--     한 문장이라 원자적이다 — 실패하면 아무것도 바뀌지 않고, 원인을 치운 뒤 그대로 다시 돌린다.
-- 표 단위로 준 권한은 이름 변경을 따라가지 않는다. 서비스 계정에 표 단위로 줬다면 새 이름으로 다시 준다.

ALTER TABLE shop.dead_letter_events
    RENAME TO shop.preorder_dead_letter_events,
    RENAME INDEX uq_dead_letter_message TO uq_preorder_dead_letter_message,
    RENAME INDEX ix_dead_letter_status TO ix_preorder_dead_letter_status,
    RENAME INDEX ix_dead_letter_type TO ix_preorder_dead_letter_type,
    RENAME INDEX ix_dead_letter_preorder TO ix_preorder_dead_letter_preorder,
    RENAME INDEX ix_dead_letter_customer TO ix_preorder_dead_letter_customer,
    RENAME INDEX fk_dead_letter_redriven_from TO ix_preorder_dead_letter_redriven_from,
    DROP FOREIGN KEY fk_dead_letter_preorder,
    DROP FOREIGN KEY fk_dead_letter_redriven_from,
    ADD CONSTRAINT fk_preorder_dead_letter_preorder FOREIGN KEY (preorder_id) REFERENCES shop.preorders (id),
    ADD CONSTRAINT fk_preorder_dead_letter_redriven_from
        FOREIGN KEY (redriven_from_id) REFERENCES shop.dead_letter_events (id),
    DROP CHECK ck_dead_letter_status,
    DROP CHECK ck_dead_letter_reason,
    DROP CHECK ck_dead_letter_receive_count,
    DROP CHECK ck_dead_letter_redrive_started,
    DROP CHECK ck_dead_letter_redriven,
    DROP CHECK ck_dead_letter_outcome,
    DROP CHECK ck_dead_letter_discarded,
    ADD CONSTRAINT ck_preorder_dead_letter_status
        CHECK (status IN ('OPEN','REDRIVING','REDRIVEN','SUCCEEDED','REDRIVE_FAILED','DISCARDED')),
    ADD CONSTRAINT ck_preorder_dead_letter_reason
        CHECK (failure_reason IN ('UNREADABLE_BODY','UNKNOWN_EVENT_TYPE','PROCESSING_FAILED')),
    ADD CONSTRAINT ck_preorder_dead_letter_receive_count CHECK (receive_count >= 0),
    ADD CONSTRAINT ck_preorder_dead_letter_redrive_started CHECK (
        (status IN ('REDRIVING','REDRIVEN','SUCCEEDED','REDRIVE_FAILED'))
            = (redrive_started_at IS NOT NULL AND redrive_requested_by IS NOT NULL)),
    ADD CONSTRAINT ck_preorder_dead_letter_redriven
        CHECK (status NOT IN ('REDRIVEN','SUCCEEDED','REDRIVE_FAILED') OR redriven_at IS NOT NULL),
    ADD CONSTRAINT ck_preorder_dead_letter_outcome
        CHECK ((status IN ('SUCCEEDED','REDRIVE_FAILED')) = (outcome_at IS NOT NULL)),
    ADD CONSTRAINT ck_preorder_dead_letter_discarded CHECK (
        (status = 'DISCARDED') = (discarded_at IS NOT NULL AND discarded_by IS NOT NULL AND discard_note IS NOT NULL));
