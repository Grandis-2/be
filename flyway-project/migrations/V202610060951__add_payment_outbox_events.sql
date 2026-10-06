-- payment_outbox_events — payment 의 아웃박스 표. 아웃박스 표는 서비스마다 따로 둔다(order_outbox_events 와 같은 결정).
--
-- 왜: 결제 결과(승인 · 거절)를 대상 서비스(order · draw)에 알린다. 사용자 토큰 없는 서비스 간 HTTP 는 두지 않으므로
--     결과 통지는 이벤트로만 하고, 결과 반영과 한 트랜잭션에 적는다 — 동기 응답을 잃어도 대상이 결국 결과를 받는다.
-- 칸 · 인덱스: order_outbox_events 와 같다(리스 릴레이, 미발행 행을 (published_at, publish_attempts, id) 순으로 읽는다).
--     제약 이름은 스키마 안에서 유일해야 해서 payment 접두어를 붙인다.
-- 행을 지우지 않는다(init_shop 의 원칙과 같다).

CREATE TABLE shop.payment_outbox_events (
    id               bigint      NOT NULL AUTO_INCREMENT,
    event_id         char(36)    COLLATE utf8mb4_bin NOT NULL,
    aggregate_type   varchar(30) NOT NULL,
    aggregate_id     bigint      NOT NULL,
    event_type       varchar(50) NOT NULL,
    payload          json        NOT NULL,
    publish_attempts int         NOT NULL DEFAULT 0,
    lease_until      datetime(6) NULL,
    created_at       datetime(6) NOT NULL,
    published_at     datetime(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_payment_outbox_event_id (event_id),
    KEY ix_payment_outbox_unpublished (published_at, publish_attempts, id),
    CONSTRAINT ck_payment_outbox_attempts CHECK (publish_attempts >= 0)
) ENGINE = InnoDB;
