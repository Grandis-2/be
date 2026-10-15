-- preorder_campaigns.product_title · product_status — catalog 상품명 · 판매 상태의 사본. visible 과 함께 visibility_version 이 셋의 번호가 된다.
--
-- 왜: 오픈 알림은 주기 작업이 보낸다(회원 토큰이 없어 catalog API 를 못 부르고, catalog 표는 읽지 않는다). 문구에 상품명이, 보낼지 판정에
--     공개 여부 · 판매 상태가 필요해 catalog 이벤트(PREORDER_PRODUCT_REGISTERED · CHANGED)가 실어 오는 값을 회차 행에 둔다.
-- 어떻게: catalog 의 products.listing_version 을 visibility_version 에 담고, 그보다 큰 번호가 오면 셋을 함께 덮는다.
--     칸 이름은 그대로 둔다 — 바꾸면 마이그레이션과 배포 사이에 도는 구버전이 회차를 읽지 못한다. 코드는 listingVersion 으로 부른다.
--     NULL 은 catalog 값을 받기 전(관리자가 먼저 만든 회차 · 이 칸을 모르는 catalog 의 등록)이다.
-- 채우기: 기존 행은 catalog 표에서 한 번 채운다(마이그레이션은 표 소유와 상관없이 읽는다 — 서비스 코드는 읽지 않는다).
--     공개 여부는 지금까지 catalog 가 보낸 적이 없어 모두 공개(1)였다. 이 문장은 회차 변경 이벤트를 적지 않으므로, 실제 값과 다른 행은
--     일정 번호를 올려 둔다 — 대기열은 주기마다 전체 재발행을 요청하고(waitingroom ScheduleResync PERIODIC), 번호가 큰 일정만
--     받으므로 다음 재발행에서 고친 공개 여부가 저절로 닿는다. 단 waitingroom 의 waitingroom.schedule.resync.periodic 이 켜져 있을 때만이다
--     (기본 10m, 0 이면 꺼짐). 꺼져 있거나 앞당기려면 관리자 재발행(POST /api/v1/admin/preorders/campaigns/republish)을 한다.
--     visibility_version 은 0 그대로 둔다. catalog 의 다음 변경(1 이상)이 이긴다.
-- 배포: 서비스 배포 전에 적용한다. 칸은 NULL 허용이라 구버전은 영향이 없다. 적용과 catalog 새 버전 배포 사이에 바뀐 상품명 · 상태 · 공개 여부는
--     사본에 닿지 않는다(구버전 catalog 는 빈 payload 를 보낸다) — 그 상품의 다음 변경에서 맞춰진다.
--     문장이 셋이라 중간에 실패하면 남은 문장만 다시 돌린다(일정 번호는 한 번 더 올라도 해가 없다).

ALTER TABLE shop.preorder_campaigns
    ADD COLUMN product_title  varchar(100) NULL AFTER visibility_version,
    ADD COLUMN product_status varchar(20)  NULL AFTER product_title,
    ADD CONSTRAINT ck_campaign_product_status CHECK (product_status IN ('ACTIVE', 'PAUSED'));

UPDATE shop.preorder_campaigns c
  JOIN shop.products p ON p.id = c.product_id
   SET c.schedule_version = c.schedule_version + 1
 WHERE c.visible <> p.visible;

UPDATE shop.preorder_campaigns c
  JOIN shop.products p ON p.id = c.product_id
   SET c.product_title = p.title, c.product_status = p.status, c.visible = p.visible;
