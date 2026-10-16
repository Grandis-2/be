-- notifications
--
-- 회원 알림(member 소유). NOTIFICATION_REQUESTED 메시지 하나가 받는 회원 1~1,000명을 싣고, 회원마다 한 행이 된다
-- (contracts/notification-events.md). 실제 발송은 없다 — 이 행이 곧 Mock 발송 기록이다. 화면에 보일 값(제목 · 본문 · 갈 곳)은
-- 보내는 쪽이 완성해 보내므로 그대로 담는다.
--
-- uq_notification_event_customer: 같은 메시지를 다시 받아도 회원마다 한 행. member 는 저장 전에 이 키의 앞부분(event_id)으로 이미 넣은
--   회원을 읽고 나머지만 넣는다. event_id 의 형 · 콜레이션은 아웃박스 event_id 와 같다.
-- customer_id: 외래 키를 두지 않는다 — 값은 보내는 서비스가 검증된 회원에서 가져오고, 키를 두면 INSERT 마다 회원 행에 공유 잠금이 걸려
--   로그인(회원 행 FOR UPDATE)과 서로 기다린다. 없는 회원을 가리키는 행을 DB 가 막지 못하는 것은 받아들인다.
-- read_at: 읽은 시각. NULL 이면 안 읽음이다. 다시 읽음 처리해도 처음 시각을 덮지 않는다.
-- aggregate_type · aggregate_id: 알림이 생긴 원인. member 는 읽지 않고 추적에만 쓴다. 봉투에 없어도 거절하지 않으려고 NULL 을 허용한다.
--
-- 인덱스
--   ix_notification_customer_occurred: 내 목록(최신순 · 커서). customer_id 등호 + (occurred_at, id) 범위를 역방향으로 훑어 정렬 없이
--     LIMIT 에서 멈춘다. id 는 InnoDB 가 어차피 끝에 붙이지만 커서의 두 번째 키라 적어 둔다.
--   ix_notification_customer_unread: 안 읽은 수(화면이 60초마다 부른다). read_at IS NULL 도 인덱스 등호로 쓰여 안 읽은 행만 인덱스 안에서
--     센다 — 표 행을 읽지 않고, 읽은 알림이 쌓여도 비용이 늘지 않는다(MySQL 8.4 EXPLAIN: ref const,const · Using index). 목록 인덱스로
--     세면 그 회원의 모든 행을 읽는다. 모두 읽음(UPDATE read_at)은 바꾸는 칸의 인덱스를 피해 목록 인덱스로 그 회원 행을 훑는다 — 드문 동작이라 둔다.
--
-- 새 표만 더하므로 서비스 배포 전에 적용한다.
CREATE TABLE shop.notifications (
    id             binary(16)   NOT NULL,
    event_id       char(36)     COLLATE utf8mb4_bin NOT NULL,
    customer_id    binary(16)   NOT NULL,
    kind           varchar(50)  NOT NULL,
    title          varchar(100) NOT NULL,
    body           varchar(500) NOT NULL,
    target_type    varchar(30)  NOT NULL,
    target_id      varchar(100) NOT NULL,
    aggregate_type varchar(30)  NULL,
    aggregate_id   binary(16)   NULL,
    occurred_at    datetime(6)  NOT NULL,
    read_at        datetime(6)  NULL,
    created_at     datetime(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_notification_event_customer (event_id, customer_id),
    KEY ix_notification_customer_occurred (customer_id, occurred_at, id),
    KEY ix_notification_customer_unread (customer_id, read_at)
) ENGINE = InnoDB;
