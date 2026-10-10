-- order_draw_results
--
-- 럭키 드로우 추첨 결과(order 소유).
-- - draw_campaigns.draw_requested_at: Mock 에 추첨을 요청한 시각(DRAW_REQUESTED 아웃박스와 같은 트랜잭션). 요청은 회차마다 한 번이다.
--   결제 완료 응모가 당첨 인원 이하면 추첨 없이 전원 당첨이라 요청하지 않는다(비어 있다).
-- - draw_campaigns.settled_at: 결과를 반영한 시각 — 당첨자마다 0원 주문, 당첨분 재고 판매 확정, 남은 확보 반납을 같은 트랜잭션에서 한다.
--   반영은 회차마다 한 번이다.
-- - draw_entries.won_order_id: 당첨 응모의 0원 주문. 결제 완료 응모에만 있다(ck_draw_entry_won_paid). 낙첨은 비어 있다.
-- - orders.source 에 DRAW(당첨 증정 0원 주문)를 더한다. 결제가 없어 결제 기한도 없다(ck_order_due).
--
-- 기존 주문 행은 PREORDER · CART 뿐이라 바뀐 CHECK 를 그대로 통과한다(새로 더한 칸은 비어 있다).
ALTER TABLE shop.draw_campaigns
    ADD COLUMN draw_requested_at datetime(6) NULL,
    ADD COLUMN settled_at        datetime(6) NULL;

ALTER TABLE shop.draw_entries
    ADD COLUMN won_order_id binary(16) NULL,
    ADD UNIQUE KEY uq_draw_entry_won_order (won_order_id),
    ADD CONSTRAINT fk_draw_entry_won_order FOREIGN KEY (won_order_id) REFERENCES shop.orders (id),
    ADD CONSTRAINT ck_draw_entry_won_paid CHECK (won_order_id IS NULL OR status = 'PAID');

ALTER TABLE shop.orders
    DROP CHECK ck_order_source,
    ADD CONSTRAINT ck_order_source CHECK (source IN ('PREORDER','BUY_NOW','CART','DRAW')),
    DROP CHECK ck_order_due,
    ADD CONSTRAINT ck_order_due CHECK ((source IN ('PREORDER','DRAW')) = (payment_due_at IS NULL));
