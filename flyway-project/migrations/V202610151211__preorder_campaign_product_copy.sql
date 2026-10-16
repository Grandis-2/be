-- preorder_campaigns.product_title · product_status — catalog 상품명 · 판매 상태의 사본. visible 과 함께 visibility_version 이 셋의 번호가 된다.
--
-- 왜: 오픈 알림은 주기 작업이 보낸다(회원 토큰이 없어 catalog API 를 못 부르고, catalog 표는 읽지 않는다). 문구에 상품명이, 보낼지 판정에
--     공개 여부 · 판매 상태가 필요해 catalog 이벤트(PREORDER_PRODUCT_REGISTERED · CHANGED)가 실어 오는 값을 회차 행에 둔다.
-- 어떻게: catalog 의 products.listing_version 을 visibility_version 에 담고, 그보다 큰 번호가 오면 셋을 함께 덮는다.
--     칸 이름은 그대로 둔다 — 바꾸면 마이그레이션과 배포 사이에 도는 구버전이 회차를 읽지 못한다. 코드는 listingVersion 으로 부른다.
-- 기존 회차는 채우지 않는다: catalog 표를 읽어야 해서다(마이그레이션도 표 소유를 지킨다). NULL 은 "catalog 값을 받기 전" 이고,
--     그 상품을 다음에 고칠 때 오는 변경 이벤트가 셋을 채운다(사본이 비어 있으면 같은 번호 0 도 받는다 — 옵션 · 가격만 고쳐도 찬다).
--     그전까지 오픈 알림은 상품명 없는 문구로 가고, 판매 상태를 모르니 판매 중지로 보지 않으며, 공개 여부는 지금 값(catalog 가 보낸 적이
--     없어 공개)이다.
-- 배포: 서비스 배포 전에 적용한다. 칸은 NULL 허용이라 구버전은 영향이 없다.

ALTER TABLE shop.preorder_campaigns
    ADD COLUMN product_title  varchar(100) NULL AFTER visibility_version,
    ADD COLUMN product_status varchar(20)  NULL AFTER product_title,
    ADD CONSTRAINT ck_campaign_product_status CHECK (product_status IN ('ACTIVE', 'PAUSED'));
