-- orders.preorder_token — 사전예약 주문이 가리키는 예약의 공개 UUID(preorders.preorder_token 과 같은 값).
--
-- 왜: order 는 예약 내부 id(preorder_id)만 저장해 왔다. preorder 의 결제 가능 확인 API 는 공개 UUID 로 묻는다.
--     결제 준비 · 승인 때 결제 가능 여부를 다시 물으려면 주문이 그 UUID 를 갖고 있어야 한다.
--     예약 취소 수신 때 봉투의 aggregateId(= preorder_id)와 payload 의 UUID 가 같은 예약인지도 이 칸으로 대조한다.
-- 값: 주문 생성 때 결제 가능 확인 응답의 preorderId 를 그대로 저장한다. 행을 만든 뒤 바꾸지 않는다.
-- 짝(preorder_id ↔ preorder_token)은 앱이 보장한다 — 같은 응답에서 둘을 함께 채운다.
--     복합 FK (preorder_id, preorder_token) → preorders(id, preorder_token) 는 두지 않는다. MySQL 8.4 는 참조 쪽에
--     완전한 UNIQUE 키를 요구해 preorders(preorder 소유)에 인덱스를 더해야 하기 때문이다.
--
-- 순서: 칸을 NULL 로 추가 → 기존 행 채우기 → 제약. 기존 PREORDER 행은 fk_order_preorder 로 예약이 반드시 있으므로 모두 채워진다.

ALTER TABLE shop.orders
    ADD COLUMN preorder_token char(36) COLLATE utf8mb4_bin NULL AFTER preorder_id;

UPDATE shop.orders o
    JOIN shop.preorders p ON p.id = o.preorder_id
SET o.preorder_token = p.preorder_token
WHERE o.preorder_token IS NULL;

ALTER TABLE shop.orders
    -- 예약당 주문은 하나(uq_order_preorder)라 토큰도 하나다. 토큰으로 찾을 때의 인덱스를 겸한다.
    ADD UNIQUE KEY uq_order_preorder_token (preorder_token),
    -- ck_order_preorder_link 와 같은 규칙: 사전예약 주문만, 그리고 반드시 갖는다.
    ADD CONSTRAINT ck_order_preorder_token CHECK ((source = 'PREORDER') = (preorder_token IS NOT NULL));
