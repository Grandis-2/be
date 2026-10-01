-- preorder_campaigns.schedule_version — 회차 일정(오픈 · 마감)의 변경 번호.
--
-- 왜: 대기열(waitingroom)은 preorder DB 를 읽지 않고 회차 변경 이벤트로 일정을 받는다. 표준 큐라 순서가 뒤집히므로
--     상품별로 더 최근 일정만 반영할 기준이 필요하다. 변경 시각은 인스턴스마다 시계가 달라 순서를 보장하지 못한다.
-- 어떻게: 일정이 실제로 바뀔 때만, 회차 행을 잠근 같은 트랜잭션에서 +1 한다(생성 = 1). 접수의 순번 발급은 올리지 않는다
--     — 그래서 JPA @Version(행이 바뀔 때마다 증가)으로 매핑하지 않는다. 회차 행은 지우지 않으므로 번호가 되감기지 않는다.
-- 값: 기존 행은 0(생성 이벤트를 낸 적 없는 회차). 전체 재발행은 이 값을 그대로 싣는다.

ALTER TABLE shop.preorder_campaigns
    ADD COLUMN schedule_version bigint NOT NULL DEFAULT 0 AFTER closes_at;
