-- preorder_sync_attempts 에 세대 · 세대 안 차례 · 결과 이벤트 id 를, preorder_sync_jobs 에 실행 회차를 더한다.
--
-- 왜: 시도 기록을 worker 가 직접 쓰지 않고, worker 가 보낸 결과 이벤트를 preorder 가 받아 남기게 됐다.
--     같은 이벤트를 두 번 받아도 한 줄만 남도록 이벤트 id 로 거른다(uq_sync_attempt_event).
--     worker 는 차례를 세대(generation)마다 1 부터 다시 세고 관리자 재처리 뒤에도 처음부터 세므로,
--     attempt_number 는 작업 안의 일련번호로 두고 worker 가 센 값은 generation · generation_attempt 에 따로 남긴다.
--     관리자 재처리 전에 돌던 실행의 결과가 늦게 와서 되돌린 작업을 다시 포기시키지 않도록, 작업에 실행 회차(run)를 둔다.
--     재처리마다 1 씩 올리고 메시지 → worker → 결과 이벤트로 돌려받아, 지난 회차의 결과는 작업 상태를 바꾸지 못한다.
-- 배포: 칸을 더하기만 한다(기본값 · NULL 허용). 구버전은 이 칸을 읽지도 쓰지도 않으므로 서비스 배포 전에 적용한다.
--     이 표에 쓰는 서비스는 아직 없다(worker 미배포).

ALTER TABLE shop.preorder_sync_attempts
    ADD COLUMN generation         int NOT NULL DEFAULT 1 AFTER attempt_number,
    ADD COLUMN generation_attempt int NOT NULL DEFAULT 1 AFTER generation,
    ADD COLUMN event_id           char(36) COLLATE utf8mb4_bin NULL,
    ADD UNIQUE KEY uq_sync_attempt_event (event_id),
    ADD CONSTRAINT ck_sync_attempt_generation CHECK (generation >= 1 AND generation_attempt >= 1);

ALTER TABLE shop.preorder_sync_jobs
    ADD COLUMN run int NOT NULL DEFAULT 1 AFTER status,
    ADD CONSTRAINT ck_sync_job_run CHECK (run >= 1);
