-- orders.paid_at(V202610161600) 기존 행 채우기.
--
-- 값: 승인 반영 이력(승인 중 → 결제 확인 대기)의 시각. 결제사 승인 시각이 아니라 반영 시각이다(대개 몇 초 늦다) — payment 표는 읽지 않는다.
--     승인은 주문당 한 번이라(결제됨에서 승인 중으로 돌아가지 않는다) 그 이력은 주문당 하나다.
-- 결제 전 상태로 남은 주문은 이력이 있어도 채우지 않는다 — 데이터가 어긋난 행 하나가 ck_order_paid 에 걸려 채우기 전체를 막지 않게.
-- 한 문장이라 실패하면 통째로 되돌아가 다시 돌릴 수 있다. 이미 채운 행은 건드리지 않는다.

UPDATE shop.orders o
  JOIN shop.order_events e ON e.order_id = o.id
   SET o.paid_at = e.created_at
 WHERE e.from_status = 'AUTHORIZING' AND e.to_status = 'AWAITING_CONFIRMATION' AND o.paid_at IS NULL
   AND o.status NOT IN ('AWAITING_PAYMENT', 'AUTHORIZING');
