package com.grandis.nova.payment.persistence.repository;

import com.grandis.nova.payment.domain.enums.TargetType;
import com.grandis.nova.payment.persistence.entity.PaymentTransactionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 상태를 바꾸는 쿼리는 모두 조건부 네이티브 UPDATE 다. 네이티브인 것은 리스 만료 · 재시도 시각을 DB 시각
 * (UTC_TIMESTAMP(6))에 간격을 더해 쓰고 같은 시각과 비교해야 해서다 — JPQL 에는 시각 산술이 없고, 앱 시계로 계산하면
 * 서버마다 시계 차만큼 리스 판정이 어긋난다. NOW(6) 가 아니라 UTC_TIMESTAMP(6) 인 것은 세션 time_zone 과 상관없이
 * 다른 시각 칼럼(UTC 로 저장)과 같은 기준이게 하려는 것이다.
 *
 * 벌크 UPDATE 는 영속성 컨텍스트를 거치지 않는다. 앞에서는 flush 하고, 뒤에서는 어댑터가 그 행의 엔티티만 떼어낸다
 * (컨텍스트 전체를 비우면 같은 트랜잭션의 다른 엔티티가 떼어져, 그 뒤의 변경이 변경 감지에 잡히지 않고 조용히 유실된다).
 */
public interface PaymentTransactionJpaRepository extends JpaRepository<PaymentTransactionJpaEntity, UUID> {

    Optional<PaymentTransactionJpaEntity> findByProviderOrderId(String providerOrderId);

    List<PaymentTransactionJpaEntity> findByTargetTypeAndTargetIdOrderByCreatedAtAscIdAsc(TargetType targetType, UUID targetId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE payment_transactions
               SET status = 'PROCESSING', provider_payment_key = :paymentKey, requested_at = :now,
                   attempt_count = attempt_count + 1,
                   lease_token = :lease, lease_expires_at = UTC_TIMESTAMP(6) + INTERVAL :leaseMicros MICROSECOND
             WHERE id = :id AND transaction_type = 'CAPTURE' AND status = 'PENDING' AND lease_token IS NULL
            """, nativeQuery = true)
    int start(@Param("id") UUID id, @Param("paymentKey") String paymentKey, @Param("lease") String lease,
              @Param("leaseMicros") long leaseMicros, @Param("now") Instant now);

    /*
     * 준비 조건은 상태 머신(TransactionStatus.claim)과 같은 것을 SQL 에도 둔다. 상태 머신은 "시도할지" 를 정하고,
     * 여기서는 시각 조건과 함께 "지금 이 행이 그 상태인지" 를 원자적으로 확인한다 — CAPTURE PENDING 을 워커가 집는 일은
     * 어느 쪽이 바뀌어도 막히게 둘 다에 둔다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE payment_transactions
               SET status = 'PROCESSING', next_retry_at = NULL, requested_at = COALESCE(requested_at, :now),
                   attempt_count = attempt_count + 1,
                   lease_token = :lease, lease_expires_at = UTC_TIMESTAMP(6) + INTERVAL :leaseMicros MICROSECOND
             WHERE id = :id AND status = :seenStatus AND lease_token <=> :seenLease AND escalated_at IS NULL
               AND (   (status = 'PENDING' AND transaction_type = 'REFUND')
                    OR (status = 'RETRY_SCHEDULED' AND next_retry_at <= UTC_TIMESTAMP(6))
                    OR (status = 'PROCESSING' AND lease_expires_at <= UTC_TIMESTAMP(6)))
            """, nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("seenStatus") String seenStatus, @Param("seenLease") String seenLease,
              @Param("lease") String lease, @Param("leaseMicros") long leaseMicros, @Param("now") Instant now);

    /*
     * 아래 셋은 리스를 쥔 작업자의 반영이다. 표식이 같아도 리스가 만료됐으면 0행이다 — 만료 뒤에는 다른 작업자가
     * 선점했을 수 있고, 아직 아무도 안 했어도 곧 할 수 있어 늦은 결과를 받지 않는다(같은 멱등 키 재전송이 결과를 다시 준다).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE payment_transactions
               SET status = :to, finished_at = :now, lease_token = NULL, lease_expires_at = NULL,
                   last_error_code = :errorCode, last_error_message = :errorMessage,
                   last_unknown_reason = :errorUnknownReason
             WHERE id = :id AND status = 'PROCESSING' AND lease_token = :lease
               AND lease_expires_at > UTC_TIMESTAMP(6)
            """, nativeQuery = true)
    int finish(@Param("id") UUID id, @Param("lease") String lease, @Param("to") String to,
               @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage,
               @Param("errorUnknownReason") String errorUnknownReason, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE payment_transactions
               SET status = 'RETRY_SCHEDULED', lease_token = NULL, lease_expires_at = NULL,
                   next_retry_at = UTC_TIMESTAMP(6) + INTERVAL :delayMicros MICROSECOND,
                   last_error_code = :errorCode, last_error_message = :errorMessage,
                   last_unknown_reason = :errorUnknownReason
             WHERE id = :id AND status = 'PROCESSING' AND lease_token = :lease
               AND lease_expires_at > UTC_TIMESTAMP(6)
            """, nativeQuery = true)
    int reschedule(@Param("id") UUID id, @Param("lease") String lease, @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage, @Param("errorUnknownReason") String errorUnknownReason,
                   @Param("delayMicros") long delayMicros);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE payment_transactions
               SET last_error_code = :errorCode, last_error_message = :errorMessage,
                   last_unknown_reason = :errorUnknownReason
             WHERE id = :id AND status = 'PROCESSING' AND lease_token = :lease
               AND lease_expires_at > UTC_TIMESTAMP(6)
            """, nativeQuery = true)
    int recordError(@Param("id") UUID id, @Param("lease") String lease, @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage, @Param("errorUnknownReason") String errorUnknownReason);

    /*
     * 만료 후보 · 만료. PENDING 은 결제창을 연 행만이고 만료로 계속 빠지므로 ix_payment_tx_recoverable 의 앞부분(status)으로 찾아 거른다.
     * 기준 시각은 확보 시각(reserved_at, DB 시각)이 있으면 그것, 없으면 연 시각(created_at, 앱 시계 UTC — 서버 시계 차만큼 어긋나지만
     * 기준(분 단위) 안의 차이다).
     */
    @Query(value = """
            SELECT * FROM payment_transactions
             WHERE status = 'PENDING' AND transaction_type = 'CAPTURE'
               AND COALESCE(reserved_at, created_at) <= UTC_TIMESTAMP(6) - INTERVAL :openedMicros MICROSECOND
             ORDER BY created_at, id
             LIMIT :limit
            """, nativeQuery = true)
    List<PaymentTransactionJpaEntity> findExpirableCaptures(@Param("openedMicros") long openedMicros,
                                                            @Param("limit") int limit);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE payment_transactions
               SET status = 'EXPIRED', finished_at = :now
             WHERE id = :id AND transaction_type = 'CAPTURE' AND status = 'PENDING' AND lease_token IS NULL
               AND COALESCE(reserved_at, created_at) <= UTC_TIMESTAMP(6) - INTERVAL :openedMicros MICROSECOND
            """, nativeQuery = true)
    int expire(@Param("id") UUID id, @Param("openedMicros") long openedMicros, @Param("now") Instant now);

    /*
     * 확보: 만료와 같은 PENDING 조건이라 둘이 겹치면 한쪽만 1행이다(행 잠금 뒤 조건을 다시 본다).
     * 결제 키는 첫 확보(reserved_at 없음)에만 적는다. 다시 확보할 때 키가 다르면 지운다 — 어느 키가 승인 중 전환과 짝인지 모르면
     * 넘기지 않는다. 지운 뒤에는 첫 확보가 아니라 다시 적히지 않는다. MySQL 은 SET 을 왼쪽부터 반영하므로 키를 reserved_at 보다 먼저 쓴다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE payment_transactions
               SET provider_payment_key = CASE
                       WHEN reserved_at IS NULL THEN :paymentKey
                       WHEN provider_payment_key = :paymentKey THEN provider_payment_key
                       ELSE NULL END,
                   reserved_at = UTC_TIMESTAMP(6)
             WHERE id = :id AND transaction_type = 'CAPTURE' AND status = 'PENDING' AND lease_token IS NULL
            """, nativeQuery = true)
    int reserve(@Param("id") UUID id, @Param("paymentKey") String paymentKey);

    /*
     * 넘김: 승인 요청이 닿지 않은 결제창을 "리스가 이미 끝난 PROCESSING" 으로 바꿔 복구가 첫 승인을 보내게 한다. 시작(start)과 같은
     * PENDING 조건이라 겹치면 한쪽만 1행이다. 보낸 횟수 · 보낸 시각은 시작과 같이 적고(PROCESSING 의 규칙), 리스 만료를 지금(DB 시각)으로
     * 둬 복구 후보(lockNextLeaseExpired)에 바로 든다. 키가 있고 금액이 알림의 금액(호출자의 저장 금액)과 같고 마지막 확보(없으면 연) 뒤
     * after 이상 · within 미만일 때만이다 — 하한은 읽은 뒤 다시 확보된 결제창을 직접 승인 요청보다 먼저 넘기지 않게 한다. 상한(10분)은
     * 만료 기준(35분)보다 짧아 둘이 겹치지 않는다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE payment_transactions
               SET status = 'PROCESSING', requested_at = :now, attempt_count = attempt_count + 1,
                   lease_token = :lease, lease_expires_at = UTC_TIMESTAMP(6)
             WHERE id = :id AND transaction_type = 'CAPTURE' AND status = 'PENDING' AND lease_token IS NULL
               AND provider_payment_key IS NOT NULL AND amount = :amount
               AND COALESCE(reserved_at, created_at) <= UTC_TIMESTAMP(6) - INTERVAL :afterMicros MICROSECOND
               AND COALESCE(reserved_at, created_at) > UTC_TIMESTAMP(6) - INTERVAL :withinMicros MICROSECOND
            """, nativeQuery = true)
    int handOver(@Param("id") UUID id, @Param("amount") BigDecimal amount, @Param("lease") String lease,
                 @Param("afterMicros") long afterMicros, @Param("withinMicros") long withinMicros,
                 @Param("now") Instant now);

    /*
     * 넘김 끄기: 시작 전 결제창의 키를 지운다. 키는 첫 확보에만 적히고 키가 있으면 이미 확보된 행이라, 지운 뒤에는 다시 적히지 않는다
     * (reserve 의 CASE). 넘김(handOver)과 같은 PENDING 조건이라 겹치면 한쪽만 된다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE payment_transactions
               SET provider_payment_key = NULL
             WHERE id = :id AND transaction_type = 'CAPTURE' AND status = 'PENDING' AND lease_token IS NULL
               AND provider_payment_key IS NOT NULL
            """, nativeQuery = true)
    int disableHandover(@Param("id") UUID id);

    /* 확보(없으면 연) 뒤 지난 시간, 마이크로초(DB 시각). 만료와 같은 기준이다. */
    @Query(value = """
            SELECT TIMESTAMPDIFF(MICROSECOND, COALESCE(reserved_at, created_at), UTC_TIMESTAMP(6))
              FROM payment_transactions
             WHERE id = :id
            """, nativeQuery = true)
    Optional<Long> microsSinceReserved(@Param("id") UUID id);

    /*
     * 복구 후보. 준비 조건마다 그 조건의 인덱스(ix_payment_tx_next_retry · ix_payment_tx_recoverable)를 타도록 따로 묻는다 — OR 로
     * 묶으면 표 전체를 훑는다. 에스컬레이션은 PROCESSING 에서만 일어나므로 리스 만료 쪽 인덱스가 escalated_at 을 품어, 에스컬레이션된
     * 행을 범위 밖에 둔다.
     */
    @Query(value = """
            SELECT * FROM payment_transactions
             WHERE status = 'RETRY_SCHEDULED' AND next_retry_at <= UTC_TIMESTAMP(6)
               AND transaction_type = :type AND escalated_at IS NULL
             ORDER BY next_retry_at
             LIMIT 1
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<PaymentTransactionJpaEntity> lockNextRetryDue(@Param("type") String type);

    @Query(value = """
            SELECT * FROM payment_transactions
             WHERE status = 'PROCESSING' AND escalated_at IS NULL AND lease_expires_at <= UTC_TIMESTAMP(6)
               AND transaction_type = :type
             ORDER BY lease_expires_at
             LIMIT 1
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<PaymentTransactionJpaEntity> lockNextLeaseExpired(@Param("type") String type);

    @Query(value = """
            SELECT * FROM payment_transactions
             WHERE status = 'PENDING' AND transaction_type = 'REFUND'
             ORDER BY created_at, id
             LIMIT 1
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<PaymentTransactionJpaEntity> lockNextPendingRefund();

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE payment_transactions
               SET escalated_at = :now, last_error_code = :errorCode, last_error_message = :errorMessage,
                   last_unknown_reason = :errorUnknownReason
             WHERE id = :id AND status = 'PROCESSING' AND lease_token = :lease
               AND lease_expires_at > UTC_TIMESTAMP(6)
            """, nativeQuery = true)
    int escalate(@Param("id") UUID id, @Param("lease") String lease, @Param("errorCode") String errorCode,
                 @Param("errorMessage") String errorMessage, @Param("errorUnknownReason") String errorUnknownReason,
                 @Param("now") Instant now);

    /*
     * 키 교체는 곧 새 키로 보낸다는 뜻이다. 리스를 새로 잡아 그 전송 하나가 리스 안에 들게 하고(조회에 쓴 시간 때문에 다른 작업자가 집지
     * 않게), 마지막 오류를 지워 교체 뒤 멈추면 다음 작업자가 "응답 못 받음 → 같은(새) 키 재전송"으로 이어 가게 한다 — 옛 오류가 남으면
     * 조회 → 또 교체로 가서 아직 진행 중일 수 있는 새 키 요청과 겹친다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE payment_transactions
               SET idempotency_key = :idempotencyKey, last_error_code = NULL, last_error_message = NULL,
                   last_unknown_reason = NULL,
                   lease_expires_at = UTC_TIMESTAMP(6) + INTERVAL :leaseMicros MICROSECOND
             WHERE id = :id AND status = 'PROCESSING' AND lease_token = :lease
               AND lease_expires_at > UTC_TIMESTAMP(6)
            """, nativeQuery = true)
    int rotateIdempotencyKey(@Param("id") UUID id, @Param("lease") String lease,
                             @Param("idempotencyKey") String idempotencyKey, @Param("leaseMicros") long leaseMicros);

    /*
     * 관리자가 에스컬레이션된 행의 리스를 넘겨받는다. 에스컬레이션은 리스를 그대로 두므로 표식은 남아 있고 70초 안에 만료된다 — 만료된 뒤 ·
     * 읽은 표식과 같을 때만이다. 워커의 선점(claim)은 escalated_at IS NULL 이라 겹치지 않는다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE payment_transactions
               SET lease_token = :lease, lease_expires_at = UTC_TIMESTAMP(6) + INTERVAL :leaseMicros MICROSECOND
             WHERE id = :id AND status = 'PROCESSING' AND escalated_at IS NOT NULL
               AND lease_token <=> :seenLease AND lease_expires_at <= UTC_TIMESTAMP(6)
            """, nativeQuery = true)
    int takeOver(@Param("id") UUID id, @Param("seenLease") String seenLease, @Param("lease") String lease,
                 @Param("leaseMicros") long leaseMicros);
}
