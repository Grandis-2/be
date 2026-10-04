-- 사전예약 회차 취소 표식. 오픈 뒤 판매 중지(회차 취소)를 접수한 시각이다 — 접수할 때 같은 트랜잭션에서 PREORDER_CAMPAIGN_CANCELED 이벤트를 적는다.
-- 상태(PAUSED)만으로는 "오픈 전에 판매 중지로 둔 채 오픈을 넘긴 상품"(회차 취소 아님, 2026-10-04 결정)과 "회차 취소를 접수한 상품"을 가를 수 없어 칸을 둔다.
-- 취소를 두 번 보내지 않는 판정이 이 칸을 본다. 아웃박스 행으로 판정하지 않는 것은 발행된 행이 보관 기간 뒤 지워질 수 있어서다.
-- 기존 행은 NULL(취소 아님). 다른 모듈의 INSERT 에 영향이 없다.
ALTER TABLE shop.products
    ADD COLUMN campaign_canceled_at datetime(6) NULL AFTER visible,
    ADD CONSTRAINT ck_product_campaign_canceled
        CHECK (campaign_canceled_at IS NULL OR (sale_mode = 'PREORDER' AND status = 'PAUSED'));
