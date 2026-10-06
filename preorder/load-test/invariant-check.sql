-- 부하 시험 뒤 한 상품의 접수 결과가 유실 · 중복 없이 맞는지 본다. 모든 *_mismatch · duplicate · gap 열이 0 이어야 한다.
-- 실행: mysql shop -e "SET @product_id = 101; SOURCE invariant-check.sql"
-- not_final 은 worker 가 외부 등록 · 취소를 마친 뒤(적체가 빠진 뒤) 0 이어야 한다.
SELECT
    COUNT(*)                                              AS total_accepted,
    SUM(p.status = 'PENDING_SYNC')                        AS pending_sync,
    SUM(p.status = 'REGISTERED')                          AS registered,
    SUM(p.status = 'CANCELING')                           AS canceling,
    SUM(p.status = 'CANCELED')                            AS canceled,
    SUM(p.status IN ('PENDING_SYNC', 'CANCELING'))        AS not_final,
    COUNT(*) - COUNT(DISTINCT p.queue_position)           AS duplicate_positions,
    COALESCE(MAX(p.queue_position), 0) - COUNT(*)         AS position_gaps,
    (SELECT c.next_queue_position - 1 FROM preorder_campaigns c WHERE c.product_id = @product_id)
        - COUNT(*)                                        AS issued_position_mismatch,
    COUNT(p.admission_ticket_id) - COUNT(DISTINCT p.admission_ticket_id) AS duplicate_tickets,
    (SELECT COUNT(*) FROM preorder_sync_jobs j JOIN preorders q ON q.id = j.preorder_id
      WHERE q.product_id = @product_id AND j.job_type = 'REGISTER') - COUNT(*)
                                                          AS register_job_mismatch,
    -- 예약마다 등록 이벤트가 정확히 하나인지. 합계만 보면 누락과 중복이 서로 상쇄된다
    (SELECT COUNT(*) FROM (
        SELECT q.id FROM preorders q
          LEFT JOIN preorder_sync_jobs j ON j.preorder_id = q.id AND j.job_type = 'REGISTER'
          LEFT JOIN preorder_outbox_events o ON o.aggregate_id = j.id AND o.aggregate_type = 'PREORDER_SYNC_JOB'
                                   AND o.event_type = 'REGISTER_JOB_READY'
         WHERE q.product_id = @product_id
         GROUP BY q.id
        HAVING COUNT(o.id) <> 1) mismatched)              AS register_event_mismatch,
    (SELECT COUNT(*) FROM preorder_outbox_events o JOIN preorder_sync_jobs j ON j.id = o.aggregate_id
                                          JOIN preorders q ON q.id = j.preorder_id
      WHERE q.product_id = @product_id AND o.event_type = 'REGISTER_JOB_READY' AND o.published_at IS NULL)
                                                          AS unpublished_events
FROM preorders p
WHERE p.product_id = @product_id;
