-- 취소 중 환불이 확정 실패한 시각을 주문에 적는다.
--
-- 왜: 환불 확정 실패는 이력(order_events)의 사유 문자열 'REFUND_FAILED' 로만 남고, "마지막 이력이 그 사유인가" 로 판정해 왔다.
--     자유 문자열이 판정 표식이라, 문구가 바뀌거나 그 뒤에 다른 이력이 붙으면 판정이 달라진다.
--     판정(같은 실패를 두 번 적지 않기 · 화면의 환불 실패 표시)은 이 칸으로 하고, 이력의 사유는 사람이 읽는 기록으로 남긴다.
-- 값: 처음 실패를 적은 시각. 취소 중에만 적고, 환불이 끝나 취소되면 기록으로 남는다(CHECK).
--
-- 칸을 더하기만 하고 구버전은 이 칸을 모르므로 같이 돌아도 된다 — 새 버전보다 먼저 적용한다.
-- 지금 행은 "취소 중이고 마지막 이력이 환불 실패" 인 주문을 그 이력 시각으로 채운다. 주문의 이력 번호는 마지막 이력의 번호다.
-- 이 뒤 구버전이 남긴 환불 실패는 이 칸이 비어 화면에 실패로 보이지 않는다 — 구버전을 끈 뒤 같은 채우기를 한 번 더 돌린다.
-- 배포: CHECK 추가는 기존 행을 검사하므로 ALGORITHM=COPY 다(표 복사 · 그동안 쓰기 막힘). 지금 orders 크기면 짧다.
--     orders 를 외래 키로 가리키는 표(order_items · order_events 등)에 열린 트랜잭션이 있으면 시작 전에 기다린다.

ALTER TABLE shop.orders
    ADD COLUMN refund_failed_at datetime(6) NULL AFTER stock_released_at,
    ADD CONSTRAINT ck_order_refund_failed CHECK (refund_failed_at IS NULL OR status IN ('CANCELING', 'CANCELED'));

UPDATE shop.orders o
  JOIN shop.order_events e ON e.order_id = o.id AND e.event_sequence = o.event_sequence
   SET o.refund_failed_at = e.created_at
 WHERE o.status = 'CANCELING' AND e.actor = 'SYSTEM' AND e.reason = 'REFUND_FAILED';
