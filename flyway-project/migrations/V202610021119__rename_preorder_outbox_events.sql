-- outbox_events → preorder_outbox_events. 아웃박스 표는 서비스마다 두고 이름에 서비스가 드러난다(order_outbox_events 와 짝).
--
-- 왜: order 행을 옮긴 뒤(V202610012322) 이 표에는 preorder 행만 남았다. 공통 모듈(common:outbox)은 표의 행을
--     모두 그 서비스 것으로 보고 종류로 거르지 않는다.
-- 어떻게: 운영 배포 전이라 새 표 + 행 이동 대신 이름만 바꾼다. 인덱스 · CHECK 이름도 표에 맞춘다.
--     미발행 인덱스는 릴레이 정렬(ORDER BY publish_attempts, id)에 맞춰 칸을 바꾼다(order 와 같다).
--     CHECK 는 이름을 바꿀 수 없어 지우고 다시 건다.
--     한 문장이라 원자적이다 — 실패하면 아무것도 바뀌지 않고, 원인을 치운 뒤 그대로 다시 돌린다.
-- 표 단위로 준 권한은 이름 변경을 따라가지 않는다. 서비스 계정에 표 단위로 줬다면 새 이름으로 다시 준다.

ALTER TABLE shop.outbox_events
    RENAME TO shop.preorder_outbox_events,
    RENAME INDEX uq_outbox_event_id TO uq_preorder_outbox_event_id,
    DROP INDEX ix_outbox_unpublished,
    ADD INDEX ix_preorder_outbox_unpublished (published_at, publish_attempts, id),
    DROP CHECK ck_outbox_attempts,
    ADD CONSTRAINT ck_preorder_outbox_attempts CHECK (publish_attempts >= 0);
