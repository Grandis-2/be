-- preorder_campaigns.visible · visibility_version — 상품 공개 여부와 그 변경 번호.
--
-- 왜: 대기열(waitingroom)은 회차 변경 이벤트만 보고 줄을 연다. 비공개 상품도 오픈 시각에 줄이 열려,
--     입장한 회원의 접수가 "상품 없음"(404)으로 거절됐다. 회차 이벤트에 공개 여부를 실어 대기열이 줄을 멈추게 한다.
-- 어떻게: 공개 여부의 원장은 catalog 다. catalog 이벤트의 visibilityVersion 이 지금 값보다 클 때만 반영한다(표준 큐라
--     순서가 뒤집힌다). 값이 실제로 바뀌면 같은 트랜잭션에서 schedule_version 도 +1 해 대기열이 새 값을 받게 한다.
-- 값: 기존 행은 공개(1) · 번호 0 — 지금까지 대기열이 공개로 다뤄 온 것과 같고, catalog 의 다음 이벤트(번호 1 이상)가 이긴다.

ALTER TABLE shop.preorder_campaigns
    ADD COLUMN visible            tinyint(1) NOT NULL DEFAULT 1 AFTER schedule_version,
    ADD COLUMN visibility_version bigint     NOT NULL DEFAULT 0 AFTER visible;
