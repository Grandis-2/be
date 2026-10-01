-- 관리자 상품 등록이 preorder · order 에 넣을 값(사전예약 회차 · 배송 차수, 일반 상품의 옵션별 초기 재고)을 등록 기록에 고정한다.
-- 최초 등록 때 한 번 쓰고 바꾸지 않는다. 등록을 이어서 진행할 때는 이 값만 쓰고 같은 키로 다시 온 본문은 보지 않는다.
-- preorder 가 외부 등록 본문을 접수 때 preorder_sync_jobs.request_payload 에 고정하는 것과 같은 방식이다.
-- 이미 있는 행은 SQL NULL 이 아니라 JSON null 이 된다(MySQL 8.4.11 실측). 앱은 그것을 "저장된 값 없음" 으로 읽고 그 등록을 막힘으로 돌려준다.
ALTER TABLE shop.product_registrations
    ADD COLUMN plan_payload json NOT NULL AFTER requested_visible;
