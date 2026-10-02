-- catalog 가 다른 서비스로 보내는 이벤트의 아웃박스. 업무 변경(상품 등록)과 같은 트랜잭션에서 INSERT 하고, 커밋 뒤 발행기 · 릴레이가 SQS 로 보낸다.
-- 서비스마다 아웃박스 표를 나눈다(2026-09-29 팀 결정) — preorder · order 의 outbox_events 와 칼럼은 같고 catalog 만 쓴다.
-- lease_until: 릴레이가 행을 가져간 기한. 짧은 트랜잭션에서 SKIP LOCKED 로 잠가 리스를 걸고 커밋한 뒤 트랜잭션 밖에서 보낸다
--   (V202609301133 과 같은 방식 — 보내는 동안 잠금 · 커넥션을 쥐지 않는다).
CREATE TABLE shop.catalog_outbox_events (
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
    UNIQUE KEY uq_catalog_outbox_event_id (event_id),
    KEY ix_catalog_outbox_unpublished (published_at, id),
    CONSTRAINT ck_catalog_outbox_attempts CHECK (publish_attempts >= 0)
) ENGINE = InnoDB;
