-- preorder_campaign_close_notices — 회차 마감을 알렸다는 표시(preorder 소유). 마감 하나(상품 · 그 마감 시각)가 한 행이다.
--
-- preorder 의 주기 작업이 마감 시각이 지난 회차를 찾아 이 표에 INSERT IGNORE 하고, 넣었으면 같은 트랜잭션에서 아웃박스에
-- PREORDER_CAMPAIGN_CLOSED(catalog-events)를 적는다(contracts/catalog-inbound-events.md). 인스턴스마다 돌아도 PK 가 한 번만 받는다.
--   왜 회차 행에 표시하지 않나: 회차 행은 접수가 FOR UPDATE 로 줄 서는 행이라, 거기에 UPDATE 하면 알림이 접수 줄에 선다.
--   PK (product_id, closes_at): 다시 열면 마감 시각이 바뀌어 다시 닫힐 때 한 번 더 보낸다(건수가 바뀐다). 일정 번호는 공개 여부가
--     바뀌어도 올라 키로 쓰면 마감 뒤 공개 전환에 또 나간다.
--   외래 키: 회차(같은 서비스). INSERT 가 회차 행에 공유 잠금을 거는데, 작업이 이미 그 행을 FOR SHARE 로 읽은 뒤라 더 기다리지 않는다.
--
-- ix_campaign_closes: 주기 작업이 30초마다 "최근 1시간에 닫힌" 회차를 찾는다.
--
-- 새 표 · 색인만 더하므로 서비스 배포 전에 적용한다.
CREATE TABLE shop.preorder_campaign_close_notices (
    product_id binary(16)  NOT NULL,
    closes_at  datetime(6) NOT NULL,
    created_at datetime(6) NOT NULL,
    PRIMARY KEY (product_id, closes_at),
    CONSTRAINT fk_close_notice_campaign FOREIGN KEY (product_id) REFERENCES shop.preorder_campaigns (product_id)
) ENGINE = InnoDB;

ALTER TABLE shop.preorder_campaigns ADD KEY ix_campaign_closes (closes_at);
