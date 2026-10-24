-- payment_dead_letter_events — payment-events DLQ 적재(payment 소유). preorder_dead_letter_events 와 같은 방식이다.
--
-- 왜: payment-events(환불 요청)에서 재수신 한도를 넘긴 메시지는 DLQ 에만 남는다. 꺼내 보면 수신 횟수 · 가시성이 바뀌고,
--     주문 · 종류로 찾을 수 없으며, 보존 기간(최대 14일)이 지나면 사라진다 — 그 주문은 환불되지 않은 채 남는다.
-- 옮기기 · 상태 · 결과 추적: preorder_dead_letter_events(V202609301538)와 같다. DLQ 소비자가 이 표에 쓰고 커밋한 뒤에만 지우고,
--     (source_queue, message_id) UNIQUE 로 한 번만 쌓인다. OPEN → REDRIVING → REDRIVEN → SUCCEEDED / REDRIVE_FAILED, OPEN → DISCARDED.
--     되돌릴 때 메시지 속성 deadLetterId 에 이 행의 id 를 싣고, 또 실패해 온 새 행은 redriven_from_id 로 앞선 행을 가리킨다.
-- preorder 표와 다른 점: 예약 · 회원 칸(preorder_id · customer_id)과 그 인덱스 · 외래 키가 없다. 주문은 봉투의 aggregate 로 찾는다 —
--     주문 이벤트는 aggregate_type 'ORDER' 이고 aggregate_id 가 주문 id 다(ix_payment_dead_letter_aggregate). 주문 표는 order 소유라
--     외래 키를 걸지 않는다. 제약 이름은 스키마 안에서 유일해야 해서 payment 접두어를 붙인다.
-- 행을 지우지 않는다. 새 표만 더하므로 서비스 배포 전에 적용한다.
CREATE TABLE shop.payment_dead_letter_events (
    id                   binary(16)   NOT NULL,
    -- 논리 큐 이름(payment-events).
    source_queue         varchar(80)  NOT NULL,
    message_id           varchar(100) COLLATE utf8mb4_bin NOT NULL,
    event_id             char(36)     COLLATE utf8mb4_bin NULL,
    -- 받는 종류(enum 이름)와 대소문자까지 같아야 같은 종류다. 대기 게이지가 이 값을 태그로 쓴다.
    event_type           varchar(50)  COLLATE utf8mb4_bin NULL,
    aggregate_type       varchar(30)  NULL,
    aggregate_id         binary(16)   NULL,
    -- SQS 본문 최대 256KB 를 담는다.
    body                 mediumtext   NOT NULL,
    -- 적재 때 본문으로 가른 분류. 실제 예외는 message_id 로 처리 실패 로그에서 찾는다.
    failure_reason       varchar(30)  NOT NULL,
    receive_count        int          NOT NULL,
    -- SQS 가 처음 받은 시각(SentTimestamp). 보존 기간 계산의 기준이다.
    sent_at              datetime(6)  NULL,
    -- 되돌렸다가 또 실패해 쌓인 행이면 앞선 행.
    redriven_from_id     binary(16)   NULL,
    status               varchar(20)  NOT NULL,
    -- 되돌리기를 시작한 관리자(order 가 실어 보낸 관리자 토큰의 주체)와 시각. 다시 선점하면 새 값으로 바뀐다.
    redrive_requested_by varchar(64)  NULL,
    redrive_started_at   datetime(6)  NULL,
    redriven_at          datetime(6)  NULL,
    -- 되돌린 메시지의 결과(SUCCEEDED · REDRIVE_FAILED)를 안 시각.
    outcome_at           datetime(6)  NULL,
    discarded_by         varchar(64)  NULL,
    discarded_at         datetime(6)  NULL,
    discard_note         varchar(500) NULL,
    created_at           datetime(6)  NOT NULL,
    updated_at           datetime(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_payment_dead_letter_message (source_queue, message_id),
    KEY ix_payment_dead_letter_status        (status, created_at),
    KEY ix_payment_dead_letter_type          (event_type, created_at),
    KEY ix_payment_dead_letter_aggregate     (aggregate_type, aggregate_id),
    KEY ix_payment_dead_letter_redriven_from (redriven_from_id),
    CONSTRAINT fk_payment_dead_letter_redriven_from
        FOREIGN KEY (redriven_from_id) REFERENCES shop.payment_dead_letter_events (id),
    CONSTRAINT ck_payment_dead_letter_status
        CHECK (status IN ('OPEN','REDRIVING','REDRIVEN','SUCCEEDED','REDRIVE_FAILED','DISCARDED')),
    CONSTRAINT ck_payment_dead_letter_reason
        CHECK (failure_reason IN ('UNREADABLE_BODY','UNKNOWN_EVENT_TYPE','PROCESSING_FAILED')),
    CONSTRAINT ck_payment_dead_letter_receive_count CHECK (receive_count >= 0),
    -- 되돌리기를 시작한 상태만 누가 · 언제 시작했는지 갖는다.
    CONSTRAINT ck_payment_dead_letter_redrive_started CHECK (
        (status IN ('REDRIVING','REDRIVEN','SUCCEEDED','REDRIVE_FAILED'))
            = (redrive_started_at IS NOT NULL AND redrive_requested_by IS NOT NULL)),
    CONSTRAINT ck_payment_dead_letter_redriven
        CHECK (status NOT IN ('REDRIVEN','SUCCEEDED','REDRIVE_FAILED') OR redriven_at IS NOT NULL),
    CONSTRAINT ck_payment_dead_letter_outcome
        CHECK ((status IN ('SUCCEEDED','REDRIVE_FAILED')) = (outcome_at IS NOT NULL)),
    CONSTRAINT ck_payment_dead_letter_discarded CHECK (
        (status = 'DISCARDED') = (discarded_at IS NOT NULL AND discarded_by IS NOT NULL AND discard_note IS NOT NULL))
) ENGINE = InnoDB;
