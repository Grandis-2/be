-- admin_alerts
--
-- 관리자 알림(member 소유). 관리자 화면의 알림창(관리자용 · 개발자용 탭)에 보일 알림이다. 두 경로로 받는다 — admin-alert 큐의
-- ADMIN_ALERT_REQUESTED(contracts/admin-alert-events.md)와 Grafana 경보(contracts/admin-alert-grafana.md). 받는 사람 · 심각도 ·
-- 묶음 · 재알림 · 해소 규칙은 member 가 정한다(contracts/admin-alert-hub.md). 한 행 = 한 사건 · 한 받는 사람이다.
--
-- uq_admin_alert_episode: 같은 사건을 다시 받아도 받는 사람마다 한 행. 중복은 eventId 가 아니라 이 키로 막는다.
--   episode 는 Grafana 경보의 시작 시각(startsAt), 이벤트는 회차 id(campaignId · 드로우 회차 id), 모르는 type 의 이벤트는 eventId 다.
--   target_id 는 대상이 없으면 '' 다 — NULL 이면 유니크 키가 같은 사건을 가르지 못한다.
--   type · target_id · episode 는 식별자라 utf8mb4_bin 이다 — 기본 콜레이션(대소문자 무시)이면 대소문자만 다른 값이 한 사건으로 묶인다.
--   type 도 모르는 종류는 보내는 쪽의 문자열 그대로라 같다.
-- target_name: 받을 때 공개 조회로 채운 이름(스냅숏). 비공개 상품이거나 조회에 실패하면 NULL 이고, 정리 작업이 하루 안의 것을 다시 채운다.
-- severity: 심각도. QUEUE_SERVER_UNHEALTHY 는 5분이 지나면 URGENT 로 바뀐다.
-- raised_at: 마지막으로 "새 알림"으로 올린 시각(생김 · 재알림 · 해소). 목록 정렬 키다. 올릴 때 read_at 을 NULL 로 되돌린다.
-- raised_value: 올릴 때의 주 숫자. 재알림(2배 · 100 단위 · 5분 뒤 증가)을 이 값과 견준다.
-- read_at: 읽은 시각(전역 하나 — 관리자 계정이 하나다). NULL 이면 안 읽음이다.
-- changed_at: 어떤 칸이든 바꾼 시각. 실시간 연결이 이 시각으로 바뀐 행을 찾는다.
-- metrics: 마지막으로 받은 값. 칸 이름은 type 마다 계약 문서가 정한다.
-- last_event_id: 마지막으로 반영한 이벤트의 eventId(추적용, Grafana 경로는 NULL). 형 · 콜레이션은 아웃박스 event_id 와 같다.
--
-- 인덱스
--   ix_admin_alert_list · ix_admin_alert_raised: 목록(raised_at · id 내림차순 커서). 탭(audience)을 고르면 앞의 것, 안 고르면 뒤의 것을
--     역방향으로 훑어 LIMIT 에서 멈춘다. 상태 · 심각도 · 안 읽음 조건은 훑으며 거른다 — 행이 30일치(수천 건 이하)라 따로 두지 않는다.
--   ix_admin_alert_unread: 안 읽은 수. read_at IS NULL 범위만 인덱스 안에서 audience · severity 로 센다.
--   ix_admin_alert_group: 받는 사람(audience)을 묻지 않는 type · 대상 조회 — 같은 회차의 DRAW_SETTLED 확인과 DRAW_DELAYED 해소. 같은 묶음의 앞 사건 닫기 ·
--     늦게 온 경보 거르기는 audience 까지 보지만 행이 적어 이 인덱스의 앞부분으로 찾는다.
--   ix_admin_alert_changed: 실시간 연결의 꼬리 읽기(changed_at · id 범위).
--   ix_admin_alert_resolved: 보관 정리(해소 뒤 30일).
--
-- 새 표만 더하므로 서비스 배포 전에 적용한다.
CREATE TABLE shop.admin_alerts (
    id                binary(16)    NOT NULL,
    type              varchar(40)   COLLATE utf8mb4_bin NOT NULL,
    audience          varchar(10)   NOT NULL,
    target_kind       varchar(20)   NOT NULL,
    target_id         varchar(100)  COLLATE utf8mb4_bin NOT NULL,
    target_name       varchar(200)  NULL,
    episode           varchar(64)   COLLATE utf8mb4_bin NOT NULL,
    source            varchar(10)   NOT NULL,
    severity          varchar(10)   NOT NULL,
    status            varchar(10)   NOT NULL,
    metrics           json          NOT NULL,
    first_occurred_at datetime(6)   NOT NULL,
    last_updated_at   datetime(6)   NOT NULL,
    raised_at         datetime(6)   NOT NULL,
    raised_value      decimal(20,4) NULL,
    resolved_at       datetime(6)   NULL,
    read_at           datetime(6)   NULL,
    changed_at        datetime(6)   NOT NULL,
    last_event_id     char(36)      COLLATE utf8mb4_bin NULL,
    created_at        datetime(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_admin_alert_episode (type, audience, target_kind, target_id, episode),
    KEY ix_admin_alert_list (audience, raised_at, id),
    KEY ix_admin_alert_raised (raised_at, id),
    KEY ix_admin_alert_unread (read_at, audience, severity),
    KEY ix_admin_alert_group (type, target_kind, target_id, status),
    KEY ix_admin_alert_changed (changed_at, id),
    KEY ix_admin_alert_resolved (status, resolved_at)
) ENGINE = InnoDB;
