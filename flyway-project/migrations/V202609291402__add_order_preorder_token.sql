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
-- 기존 행은 채우지 않는다. preorders 는 preorder 소유라 order 의 데이터를 거기서 읽어 만들지 않는다(모듈 경계).
--     이 칸을 도입하는 시점에 orders 행을 쓰는 order 서비스가 배포된 적이 없어(dev · main 의 order 는 빈 뼈대) 채울 행이 없다.
--     사전예약 주문 행이 있는 DB 라면 CHECK 에 걸려 이 문장 전체가 실패한다 — 조용히 틀리지 않는다.
--     칸 추가와 제약을 한 ALTER 로 묶어, 실패하면 칸도 생기지 않는다(MySQL DDL 은 문장 사이에서 되돌리지 않는다).
--     그때는 order 가 preorder API 로 UUID 를 받아 채우는 별도 절차가 필요하다. 개인 로컬 DB 는 새로 만든다(README).

ALTER TABLE shop.orders
    ADD COLUMN preorder_token char(36) COLLATE utf8mb4_bin NULL AFTER preorder_id,
    -- 예약당 주문은 하나(uq_order_preorder)라 토큰도 하나다. 토큰으로 찾을 때의 인덱스를 겸한다.
    ADD UNIQUE KEY uq_order_preorder_token (preorder_token),
    -- ck_order_preorder_link 와 같은 규칙: 사전예약 주문만, 그리고 반드시 갖는다.
    ADD CONSTRAINT ck_order_preorder_token CHECK ((source = 'PREORDER') = (preorder_token IS NOT NULL));
