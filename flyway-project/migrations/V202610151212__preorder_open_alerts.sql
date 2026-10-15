-- preorder_open_alerts — 오픈 알림 신청(preorder 소유). 회원이 오픈 전 사전예약 상품에 "오픈 알림"을 걸어 둔 한 건이 한 행이다.
-- preorder_open_alert_dispatches — 회차 하나의 오픈 알림 발송 진행(preorder 소유). 오픈 표시와 함께 한 행이 생긴다(회차당 한 번 — 오픈 뒤 일정 변경 금지).
--
-- 회차가 열리면 preorder 의 주기 작업이 신청자에게 NOTIFICATION_REQUESTED(PREORDER_OPENED)를 1,000명씩 묶어 보낸다(contracts/notification-events.md "오픈 알림").
-- preorder_open_alerts
--   PK (product_id, customer_id): 같은 신청을 다시 해도 한 행이다. 발송은 PK 앞부분으로 상품의 신청자를 customer_id 순으로 끊어 읽는다.
--   ix_open_alert_customer: 내 신청 목록(customer_id = 나). 끝에 PK 의 product_id 가 붙어 회차로 바로 잇는다.
--   외래 키: 회차(같은 서비스)만 건다 — 신청 INSERT 가 회차 행에 공유 잠금을 걸어, 오픈 표시(회차 행 UPDATE)와 줄 선다.
--     회원(customers, member 소유)에는 걸지 않는다 — 값은 검증된 로그인 토큰에서 오고, 키를 두면 INSERT 마다 회원 행에 공유 잠금이 걸려
--     member 의 쓰기와 부딪힌다(V202610111301 · notifications 와 같은 판단). 탈퇴 회원의 행을 DB 가 막지 못하는 것은 받아들인다.
--   신청 행은 오픈 뒤에도 지우지 않는다(목록은 오픈 전 회차만 보인다). 보관 기간은 운영 전에 정한다.
-- preorder_open_alert_dispatches
--   왜 따로 두나: 발송은 1,000명 묶음마다 짧은 트랜잭션으로 나눠 이 행을 잠근다. 회차 행을 잡은 채 보내면 오픈 순간 그 회차의 접수(회차 행 잠금)가
--     신청자 수만큼 기다린다. 이 행을 잠그므로 여러 인스턴스가 같은 회차를 이어 보내도 묶음마다 한 번이다.
--   last_customer_id: 보낸 마지막 신청자(커서). NULL 은 아직 한 묶음도 보내기 전. 묶음의 아웃박스 행과 같은 트랜잭션에서 옮긴다.
--   completed_at: 다 보냈거나 그만둔 시각(이어 가다 마감 · 1시간 창 경과 · 비공개 · 판매 중지). NULL 인 행은 주기 작업이 이어 간다(중간에 죽은 발송도).
--   외래 키: 회차. 진행 칸 UPDATE 는 키 칸을 바꾸지 않아 회차 행을 잠그지 않는다.
--   ix_open_alert_dispatch_pending: 30초마다 "끝나지 않은 발송" 을 찾고, 지표가 그 수와 가장 오래된 시작 시각을 센다.
--
-- ix_campaign_open_notify: 주기 작업이 "오픈 표시 전 · 최근 1시간에 연" 회차를 찾고 건너뛴 회차를 센다. 회차 표는 작지만 30초마다 도는 조회라 색인으로 읽는다.
--   마감 · 공개 · 판매 조건은 넣지 않는다 — 범위(표시 전 · 최근 1시간 오픈)가 몇 행이라 행을 읽어 걸러도 싸다. 끝에 붙는 PK 로 "오픈 순 · 상품 id 순" 정렬도 색인 순서다.
--
-- 새 표 · 색인만 더하므로 서비스 배포 전에 적용한다.
CREATE TABLE shop.preorder_open_alerts (
    product_id  binary(16)  NOT NULL,
    customer_id binary(16)  NOT NULL,
    created_at  datetime(6) NOT NULL,
    PRIMARY KEY (product_id, customer_id),
    KEY ix_open_alert_customer (customer_id),
    CONSTRAINT fk_open_alert_campaign FOREIGN KEY (product_id) REFERENCES shop.preorder_campaigns (product_id)
) ENGINE = InnoDB;

CREATE TABLE shop.preorder_open_alert_dispatches (
    product_id       binary(16)  NOT NULL,
    last_customer_id binary(16)  NULL,
    created_at       datetime(6) NOT NULL,
    completed_at     datetime(6) NULL,
    PRIMARY KEY (product_id),
    KEY ix_open_alert_dispatch_pending (completed_at, created_at),
    CONSTRAINT fk_open_alert_dispatch_campaign FOREIGN KEY (product_id) REFERENCES shop.preorder_campaigns (product_id)
) ENGINE = InnoDB;

ALTER TABLE shop.preorder_campaigns
    ADD KEY ix_campaign_open_notify (open_notified_at, opens_at);
