-- orders.idempotency_key — 바로 구매 주문 생성 요청의 멱등 키(Idempotency-Key 헤더, UUID).
--
-- 왜: 바로 구매는 누를 때마다 새 주문이라 응답을 못 받은 재전송 · 두 번 전달이 주문과 재고 확보를 두 번 만든다.
--     화면이 누름마다 키 하나를 만들고 재전송에 같은 키를 실으면, 같은 회원 · 같은 키의 두 번째 INSERT 를 유일 키가 막는다.
--     "없는지 보고 넣기" 가 아니라 이 유일 키로 가른다 — 동시에 온 같은 키는 뒤의 것이 앞의 커밋을 기다렸다가 중복(또는 재고 부족)으로 끝나고,
--     어느 쪽이든 그 키의 주문과 대조해 돌려준다.
-- 값: 주문 생성 때 헤더 값을 그대로 넣는다. 행을 만든 뒤 바꾸지 않는다.
-- 규칙: 바로 구매 주문만, 그리고 반드시 갖는다(ck_order_idempotency — ck_order_preorder_token 과 같은 모양).
--     유일은 회원 안에서다(uq_order_idempotency) — 다른 회원의 같은 키는 다른 주문이다. 다른 출처는 NULL 이라 겹쳐도 된다.
-- 기존 행: 바로 구매 주문은 이 칸과 같은 변경(NV-572)으로 처음 생기므로 배포된 DB 에는 없다. 있으면 CHECK 에 걸려 ALTER 전체가
--     실패한다(조용히 틀리지 않는다). 그 앞 커밋으로 바로 구매를 만들어 본 개인 로컬 DB 는 새로 만든다.
-- 형식은 다른 UUID 칸(customer_id 등)과 같은 BINARY(16) 이다.
-- 배포: CHECK 추가는 기존 행을 검사하므로 ALGORITHM=COPY 다(표 복사 · 그동안 쓰기 막힘). 칸 · 유일 키만 따로 INPLACE 로 먼저 넣어도
--     CHECK 가 결국 표를 복사하고, 문장을 나누면 중간에 실패했을 때 앞 문장만 남는다 — 한 ALTER 로 묶는다. 지금 orders 크기면 짧다.

ALTER TABLE shop.orders
    ADD COLUMN idempotency_key BINARY(16) NULL AFTER preorder_token,
    ADD UNIQUE KEY uq_order_idempotency (customer_id, idempotency_key),
    ADD CONSTRAINT ck_order_idempotency CHECK ((source = 'BUY_NOW') = (idempotency_key IS NOT NULL));
