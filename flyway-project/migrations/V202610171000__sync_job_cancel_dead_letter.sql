-- 외부 취소 작업(CANCEL)도 포기(DEAD_LETTER)로 둘 수 있게 ck_sync_job_dead_letter_register_only 를 지운다.
--
-- 왜: worker 가 외부 취소를 포기하면(외부 거절 · 보낼 내용 오류) 지금은 작업이 진행 중으로 남고 예약은 CANCELING 에서 멈춘다 —
--     관리자 재처리(DEAD_LETTER 만 받는다)로도, 목록 · 지표로도 드러나지 않는다. 등록 작업처럼 포기를 남겨 재처리 · 수동 완료의 대상이 되게 한다.
--     ck_sync_job_dead_letter(DEAD_LETTER 면 dead_lettered_at 이 있다)는 두 종류 모두에 그대로 걸린다.
--     ck_sync_job_canceled_register_only 는 남긴다 — 무효화(CANCELED)는 취소가 등록을 멈출 때만 쓴다.
-- 잃는 것: CANCEL 작업에 DEAD_LETTER 를 적는 잘못을 DB 가 막지 못한다. 포기는 worker 결과 이벤트로만 적는다.
--
-- 구버전이 이미 포기한 CANCEL 작업은 진행 중으로 남아 있다. 마지막 차례가 REJECTED 인 것만 DEAD_LETTER 로 옮긴다 —
--     worker 는 REJECTED 면 다시 시도하지 않는다. 재시도를 다 써서 포기한 작업은 기록으로 가릴 수 없어 그대로 둔다.
--     구버전은 CANCEL 을 재처리하지 않았으므로 CANCEL 의 차례는 모두 실행 회차 1 의 것이다.
-- 구버전은 CANCEL 을 DEAD_LETTER 로 적지 않으므로 서비스 배포 전에 적용한다.
-- 배포: CHECK 삭제는 메타데이터만 바꾸지만 열린 트랜잭션이 있으면 메타데이터 잠금을 기다리고, 그동안 이 표의 조회도 뒤에 선다.
ALTER TABLE shop.preorder_sync_jobs
    DROP CHECK ck_sync_job_dead_letter_register_only;

UPDATE shop.preorder_sync_jobs j
  JOIN shop.preorders p ON p.id = j.preorder_id
  JOIN shop.preorder_sync_attempts a ON a.sync_job_id = j.id
   AND a.attempt_number = (SELECT MAX(l.attempt_number) FROM shop.preorder_sync_attempts l WHERE l.sync_job_id = j.id)
   SET j.status = 'DEAD_LETTER',
       j.dead_lettered_at = COALESCE(a.finished_at, a.started_at),
       j.updated_at = UTC_TIMESTAMP(6)
 WHERE j.job_type = 'CANCEL'
   AND j.status IN ('PENDING', 'PROCESSING', 'RETRY_SCHEDULED')
   AND p.status = 'CANCELING'
   AND a.result = 'REJECTED';
