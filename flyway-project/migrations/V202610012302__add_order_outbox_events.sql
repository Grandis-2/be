-- order_outbox_events — order 의 아웃박스 표. 아웃박스 표는 서비스마다 따로 둔다.
--
-- 왜: 공유 표(outbox_events)에서는 릴레이마다 event_type 으로 자기 행을 골라야 하고, event_type 이 전역 이름이라
--     두 서비스가 같은 이름을 쓰면 남의 행을 자기 목적지로 보낸다. 공통 모듈(common:outbox)은 표를 모르고
--     서비스가 표 이름을 준다 — 서비스 표의 행은 모두 그 서비스 것이라 종류로 거르지 않는다.
-- 칸: outbox_events 와 같고 lease_until 이 처음부터 있다(리스 릴레이 — V202609301133 참고).
--     CHECK 이름은 스키마 안에서 유일해야 해서 ck_order_outbox_attempts 다.
-- 인덱스: 릴레이는 미발행 행을 ORDER BY publish_attempts, id 로 가져간다(늘 실패하는 행이 앞을 막지 않게).
--     (published_at, publish_attempts, id) 로 정렬 없이 읽는다.
-- 행을 지우지 않는다(init_shop 의 원칙과 같다). 기존 order 행은 다음 마이그레이션이 옮긴다 — 표 생성(DDL)과 따로 두어,
--     옮기다 실패하면 행 이동만 통째로 롤백되고 다시 돌릴 수 있게 한다.

CREATE TABLE shop.order_outbox_events (
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
    UNIQUE KEY uq_order_outbox_event_id (event_id),
    KEY ix_order_outbox_unpublished (published_at, publish_attempts, id),
    CONSTRAINT ck_order_outbox_attempts CHECK (publish_attempts >= 0)
) ENGINE = InnoDB;
