-- preorder_sync_attempts 에 세대 · 세대 안 차례 · 결과 이벤트 id 를 더한다.
--
-- 왜: 시도 기록을 worker 가 직접 쓰지 않고, worker 가 보낸 결과 이벤트를 preorder 가 받아 남기게 됐다.
--     같은 이벤트를 두 번 받아도 한 줄만 남도록 이벤트 id 로 거른다(uq_sync_attempt_event).
--     worker 는 차례를 세대(generation)마다 1 부터 다시 세고 관리자 재처리 뒤에도 처음부터 세므로,
--     attempt_number 는 작업 안의 일련번호로 두고 worker 가 센 값은 generation · generation_attempt 에 따로 남긴다.
-- 배포: 칸을 더하기만 한다(기본값 · NULL 허용). 구버전은 이 칸을 읽지도 쓰지도 않으므로 서비스 배포 전에 적용한다.
--     이 표에 쓰는 서비스는 아직 없다(worker 미배포).

ALTER TABLE shop.preorder_sync_attempts
    ADD COLUMN generation         int NOT NULL DEFAULT 1 AFTER attempt_number,
    ADD COLUMN generation_attempt int NOT NULL DEFAULT 1 AFTER generation,
    ADD COLUMN event_id           char(36) COLLATE utf8mb4_bin NULL,
    ADD UNIQUE KEY uq_sync_attempt_event (event_id),
    ADD CONSTRAINT ck_sync_attempt_generation CHECK (generation >= 1 AND generation_attempt >= 1);
