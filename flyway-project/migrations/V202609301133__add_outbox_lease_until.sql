-- outbox_events.lease_until — 릴레이가 행을 "가져간" 기한(리스).
--
-- 왜: 릴레이가 미발행 행을 FOR UPDATE 로 잠근 채 큐로 보내면, 큐가 느리거나 죽었을 때 전송 시간 내내
--     트랜잭션 · 행 잠금 · DB 커넥션을 붙잡는다(배치 100 × 전송 제한 3s ≈ 5분). 접수 경로가 커넥션을 못 얻는다.
-- 어떻게: 가져가기와 보내기를 나눈다(트랜잭셔널 아웃박스의 리스 방식).
--     ① 짧은 트랜잭션: 리스가 없거나 끝난 미발행 행을 SKIP LOCKED 로 잠가 lease_until = 지금 + 리스 시간 → 커밋(잠금 해제)
--     ② 트랜잭션 밖에서 전송
--     ③ 행마다 짧은 트랜잭션: 성공이면 published_at, 실패면 publish_attempts + 1 · lease_until = NULL
--     리스 동안 다른 인스턴스는 그 행을 건너뛴다. 보내던 인스턴스가 죽으면 리스가 끝난 뒤 다른 인스턴스가 이어 보낸다.
-- 값: NULL = 아무도 가져가지 않음. 발행을 마친 행의 값은 마지막 리스 기록이며 쓰이지 않는다.
--
-- 인덱스는 더하지 않는다. 릴레이는 미발행 행(published_at IS NULL, 평소 0 ~ 수십 행)만 보므로
--     기존 ix_outbox_unpublished (published_at, id) 로 좁힌 뒤 lease_until 을 걸러도 충분하다.
-- 칸만 더한다(NULL 허용, 기본값 없음) — 이 칸을 모르는 서비스(order)의 기존 코드와 기존 행은 그대로 동작한다.

ALTER TABLE shop.outbox_events
    ADD COLUMN lease_until datetime(6) NULL AFTER publish_attempts;
