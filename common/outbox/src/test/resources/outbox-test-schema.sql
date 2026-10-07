-- common:outbox 시험 전용 표. 공통 모듈은 표를 소유하지 않으므로 마이그레이션 대신 여기서 만든다.
-- 칸 · 형은 서비스 아웃박스 표(order_outbox_events 등)와 같게 둔다. 실제 마이그레이션과의 일치는 서비스의 배선 시험이 본다.
CREATE TABLE IF NOT EXISTS it_outbox_events (
    id               bigint      NOT NULL AUTO_INCREMENT,
    event_id         char(36)    COLLATE utf8mb4_bin NOT NULL,
    aggregate_type   varchar(30) NOT NULL,
    aggregate_id     binary(16)  NOT NULL,
    event_type       varchar(50) NOT NULL,
    payload          json        NOT NULL,
    publish_attempts int         NOT NULL DEFAULT 0,
    lease_until      datetime(6) NULL,
    created_at       datetime(6) NOT NULL,
    published_at     datetime(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_it_outbox_event_id (event_id),
    KEY ix_it_outbox_unpublished (published_at, publish_attempts, id)
) ENGINE = InnoDB;

-- 아웃박스와 한 트랜잭션으로 묶이는 업무 변경(JPA 엔티티)
CREATE TABLE IF NOT EXISTS it_business_records (
    id   bigint      NOT NULL AUTO_INCREMENT,
    name varchar(50) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

-- 칸이 빠진 표(lease_until 없음). 기동 검사가 막는지 본다
CREATE TABLE IF NOT EXISTS it_outbox_without_lease (
    id               bigint      NOT NULL AUTO_INCREMENT,
    event_id         char(36)    NOT NULL,
    aggregate_type   varchar(30) NOT NULL,
    aggregate_id     binary(16)  NOT NULL,
    event_type       varchar(50) NOT NULL,
    payload          json        NOT NULL,
    publish_attempts int         NOT NULL DEFAULT 0,
    created_at       datetime(6) NOT NULL,
    published_at     datetime(6) NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;
