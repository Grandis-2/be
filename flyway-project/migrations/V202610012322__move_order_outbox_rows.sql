-- outbox_events 의 order 행(PREORDER_ORDER_SETTLED)을 order_outbox_events 로 옮긴다. 발행된 행도 옮긴다.
--
-- 왜: 남겨 두면 preorder 가 자기 표로 옮겨 종류로 거르지 않게 됐을 때 미발행 order 행을 집어 실패를 반복한다.
--     공유 표에서는 지우지만 행은 새 표로 옮겨 그대로 남는다 — init_shop 의 "행을 지우지 않는다"의 예외가 아니다.
-- 어떻게: event_id 는 그대로 둔다(소비자의 중복 판정 기준). id 는 새로 받는다 — 다시 돌릴 때 그 사이 새 표에 생긴 id 와
--     부딪히지 않게. 리스는 옮기지 않는다(다른 표의 리스라 의미가 없다). 칸은 이름으로 적어 lease_until 유무와 상관없이 동작한다.
-- 표 생성(V202610012302)과 파일을 나눈 것은 DML 만 있어야 한 트랜잭션으로 돌기 때문이다 — 구버전 order 릴레이가
--     행 잠금을 쥔 동안(최악 수 분) 돌려 잠금 대기 시간을 넘기면 이 파일만 통째로 롤백되고, 원인을 치운 뒤 다시 돌린다.
-- 다시 돌려도 결과가 같다. 이 마이그레이션 뒤에도 구버전 order 가 잠시 outbox_events 에 적을 수 있으므로,
--     구버전이 모두 내려간 뒤 아래 두 문장을 한 번 더 돌리면 그 사이 행도 옮겨진다.

INSERT INTO shop.order_outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload,
                                      publish_attempts, created_at, published_at)
SELECT o.event_id, o.aggregate_type, o.aggregate_id, o.event_type, o.payload,
       o.publish_attempts, o.created_at, o.published_at
  FROM shop.outbox_events o
 WHERE o.event_type = 'PREORDER_ORDER_SETTLED'
   AND NOT EXISTS (SELECT 1 FROM shop.order_outbox_events n WHERE n.event_id = o.event_id)
 ORDER BY o.id;

DELETE FROM shop.outbox_events
 WHERE event_type = 'PREORDER_ORDER_SETTLED'
   AND event_id IN (SELECT event_id FROM shop.order_outbox_events);
