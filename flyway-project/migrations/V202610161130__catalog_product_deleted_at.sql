-- 상품 소프트 삭제 표식. 관리자가 상품을 삭제한 시각이다 — 행은 지우지 않는다(주문 · 장바구니 · 예약 · 드로우가 옵션을 외래키 · 스냅샷으로 가리킨다).
-- 삭제한 상품은 비공개로도 둔다(CHECK) — 회원 쪽 목록 · 상세 · 홍보 카드 · 리뷰는 이미 공개 여부로 거르므로 그대로 빠지고, 사전예약이면 공개 여부
-- 변경 이벤트(PREORDER_PRODUCT_CHANGED)가 preorder 의 회차 사본을 비공개로 바꿔 오픈 알림 · 대기열이 멈춘다. 되살리기는 없다.
-- 공개 여부를 보지 않는 경로(관리자 목록 · 상세 · 수정, 증정품 후보, 홍보 연결 후보, 내부 조회 API)는 이 칸으로 거른다.
-- 기존 행은 NULL(삭제 아님). 다른 모듈의 INSERT 에 영향이 없다.
ALTER TABLE shop.products
    ADD COLUMN deleted_at datetime(6) NULL AFTER campaign_canceled_at,
    ADD CONSTRAINT ck_product_deleted_hidden CHECK (deleted_at IS NULL OR visible = 0);
