-- 이벤트 큐 DLQ 적재 ------------------------------------------
-- preorder-events 에서 재수신 한도를 넘겨 DLQ 로 간 메시지를 DB 로 옮겨 조회 · 되돌리기 · 버리기를 한다.
--
-- 왜: DLQ 는 SQS 안에서만 보인다. 꺼내 보면 수신 횟수 · 가시성이 바뀌고, 예약 · 회원 · 종류로 찾을 수 없으며,
--     보존 기간(최대 14일)이 지나면 사라진다.
-- 옮기기: DLQ 소비자가 받아 이 표에 쓰고 커밋한 뒤에만 DLQ 에서 지운다. 지우기 전에 죽으면 다시 받지만
--     (source_queue, message_id) UNIQUE 로 한 번만 쌓인다. SQS 는 DLQ 로 옮겨도 MessageId 를 유지하므로
--     처리 실패 로그(messageId)와 이 행을 잇는다. 원문(body)을 그대로 두고, 봉투에서 읽은 칸은 검색용이다(못 읽으면 NULL).
--
-- 상태
--   OPEN → REDRIVING(원래 큐로 보내는 중) → REDRIVEN(보냄) → SUCCEEDED(처리됨) / REDRIVE_FAILED(또 DLQ 로 옴)
--   OPEN → DISCARDED(버림, 사유 필수)
-- REDRIVING 은 겹쳐 보내기를 막는 선점이다. OPEN → REDRIVING 조건부 UPDATE 가 1행일 때만 보낸다. 보내기 실패(시간 초과 등)는
--     실제로 갔는지 알 수 없어 REDRIVING 에 둔다 — 갔다면 결과가 이 행에 남는다. 보내다 죽거나 실패해 오래 남은 REDRIVING 은
--     redrive_started_at 으로 가려 다시 선점한다(이때 한 번 더 갈 수 있다 — 소비자는 멱등).
-- 결과 추적: 되돌릴 때 메시지 속성 deadLetterId 에 이 행의 id 를 싣는다.
--   처리되면 주 소비자가 SUCCEEDED 로 바꾼다. 보냄 기록(REDRIVEN)보다 처리가 먼저 끝날 수 있어 REDRIVING 에서도 바꾼다.
--   또 실패하면 SQS 가 속성째 DLQ 로 옮긴다. 새 행이 redriven_from_id 로 이 행을 가리키고, 이 행은 REDRIVE_FAILED 가 된다.
-- 행을 지우지 않는다.
CREATE TABLE shop.dead_letter_events (
    id                   bigint       NOT NULL AUTO_INCREMENT,
    -- 논리 큐 이름(preorder-events). 서비스별로 자기 큐의 DLQ 만 쌓는다.
    source_queue         varchar(80)  NOT NULL,
    message_id           varchar(100) COLLATE utf8mb4_bin NOT NULL,
    event_id             char(36)     COLLATE utf8mb4_bin NULL,
    event_type           varchar(50)  NULL,
    aggregate_type       varchar(30)  NULL,
    aggregate_id         bigint       NULL,
    -- payload.preorderId(공개 UUID)로 찾은 예약과 그 회원. 예약이 없는 이벤트(회차 판매 중지 등)는 NULL 이다.
    preorder_id          bigint       NULL,
    customer_id          bigint       NULL,
    -- SQS 본문 최대 256KB 를 담는다.
    body                 mediumtext   NOT NULL,
    -- 적재 때 본문으로 가른 분류. 실제 예외는 message_id 로 처리 실패 로그에서 찾는다.
    failure_reason       varchar(30)  NOT NULL,
    receive_count        int          NOT NULL,
    -- SQS 가 처음 받은 시각(SentTimestamp). 보존 기간 계산의 기준이다.
    sent_at              datetime(6)  NULL,
    -- 되돌렸다가 또 실패해 쌓인 행이면 앞선 행.
    redriven_from_id     bigint       NULL,
    status               varchar(20)  NOT NULL,
    -- 되돌리기를 시작한 관리자(인증 주체 이름)와 시각. 다시 선점하면 새 값으로 바뀐다.
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
    UNIQUE KEY uq_dead_letter_message (source_queue, message_id),
    KEY ix_dead_letter_status   (status, created_at),
    KEY ix_dead_letter_type     (event_type, created_at),
    KEY ix_dead_letter_preorder (preorder_id),
    KEY ix_dead_letter_customer (customer_id),
    CONSTRAINT fk_dead_letter_preorder     FOREIGN KEY (preorder_id) REFERENCES shop.preorders (id),
    CONSTRAINT fk_dead_letter_redriven_from FOREIGN KEY (redriven_from_id) REFERENCES shop.dead_letter_events (id),
    CONSTRAINT ck_dead_letter_status CHECK (status IN ('OPEN','REDRIVING','REDRIVEN','SUCCEEDED','REDRIVE_FAILED','DISCARDED')),
    CONSTRAINT ck_dead_letter_reason CHECK (failure_reason IN ('UNREADABLE_BODY','UNKNOWN_EVENT_TYPE','PROCESSING_FAILED')),
    CONSTRAINT ck_dead_letter_receive_count CHECK (receive_count >= 0),
    -- 되돌리기를 시작한 상태만 누가 · 언제 시작했는지 갖는다.
    CONSTRAINT ck_dead_letter_redrive_started CHECK (
        (status IN ('REDRIVING','REDRIVEN','SUCCEEDED','REDRIVE_FAILED'))
            = (redrive_started_at IS NOT NULL AND redrive_requested_by IS NOT NULL)),
    CONSTRAINT ck_dead_letter_redriven CHECK (status NOT IN ('REDRIVEN','SUCCEEDED','REDRIVE_FAILED') OR redriven_at IS NOT NULL),
    CONSTRAINT ck_dead_letter_outcome CHECK ((status IN ('SUCCEEDED','REDRIVE_FAILED')) = (outcome_at IS NOT NULL)),
    CONSTRAINT ck_dead_letter_discarded CHECK (
        (status = 'DISCARDED') = (discarded_at IS NOT NULL AND discarded_by IS NOT NULL AND discard_note IS NOT NULL))
) ENGINE = InnoDB;
