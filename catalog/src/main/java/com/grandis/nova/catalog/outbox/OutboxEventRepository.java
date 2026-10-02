package com.grandis.nova.catalog.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 기록(OutboxWriter)과 발행(OutboxPublisher · OutboxRelay)만 쓴다. catalog 전용 표라 이벤트 종류로 거르지 않는다.
 *
 * 리스 값은 소유 표식이라 등호로 비교한다(lease_until = :lease). 그래서 저장된 값과 넘긴 값이 같은 정밀도여야 한다 — 리스는 마이크로초로 내린
 * storageClock(JpaAuditingConfig, datetime(6) 과 같은 단위)에서 만든다. 나노초 시계로 만들면 저장 때 잘리거나 반올림돼 자기 리스와도 같지 않게 된다.
 * macOS 의 Instant.now() 는 원래 마이크로초라 로컬 시험으로는 이 의존이 드러나지 않는다(Linux 는 나노초, JDK 25 실측).
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** 발행 완료 표시. 이미 채워졌으면 바꾸지 않는다. */
    @Transactional
    @Modifying
    @Query("update OutboxEvent e set e.publishedAt = :now where e.id = :id and e.publishedAt is null")
    int markPublished(@Param("id") Long id, @Param("now") Instant now);

    /**
     * 발행 실패 횟수를 올리고, 이 리스(lease)가 아직 걸려 있으면 풀어 다음 릴레이 주기에 다시 가져가게 한다.
     * 리스 값이 소유 표식이다 — 리스가 끝나 다른 인스턴스가 새로 건 리스는 풀지 않는다. 리스 없이 보낸 경우(커밋 직후 발행)는 null.
     */
    @Transactional
    @Modifying
    @Query("""
            update OutboxEvent e set e.publishAttempts = e.publishAttempts + 1,
                   e.leaseUntil = case when e.leaseUntil = :lease then null else e.leaseUntil end
             where e.id = :id""")
    int recordFailure(@Param("id") Long id, @Param("lease") Instant lease);

    /**
     * 릴레이가 가져갈 행을 잠근다. 리스가 없거나 끝난 행만, 다른 인스턴스가 잠근 행은 건너뛴다(SKIP LOCKED).
     * 잠금은 같은 트랜잭션에서 {@link #lease} 로 리스를 건 뒤 커밋하면서 바로 푼다 — 보내는 동안 잠금 · 커넥션을 쥐지 않는다.
     * 실패가 적은 행부터 가져간다 — 늘 실패하는 행이 묶음 앞자리를 차지해 다른 행을 막지 않게.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT * FROM catalog_outbox_events
             WHERE published_at IS NULL AND created_at <= :createdBefore
               AND (lease_until IS NULL OR lease_until <= :now)
             ORDER BY publish_attempts, id
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockClaimable(@Param("createdBefore") Instant createdBefore, @Param("now") Instant now,
                                    @Param("limit") int limit);

    /** 잠근 행에 리스를 건다. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query("update OutboxEvent e set e.leaseUntil = :until where e.id in :ids")
    int lease(@Param("ids") Collection<Long> ids, @Param("until") Instant until);

    /**
     * 보내기 직전에 리스를 연장한다. 아직 내 리스(lease)이고 미발행일 때만 바꾼다 —
     * 0 이면 리스가 끝나 다른 인스턴스가 가져갔거나 이미 발행된 행이라 보내지 않는다.
     */
    @Transactional
    @Modifying
    @Query("""
            update OutboxEvent e set e.leaseUntil = :renewed
             where e.id = :id and e.leaseUntil = :lease and e.publishedAt is null""")
    int renewLease(@Param("id") Long id, @Param("lease") Instant lease, @Param("renewed") Instant renewed);

    /** 아직 보내지 못한 행 수. 늘어나면 전송이 막힌 것이다(큐 권한 · 큐 장애). */
    @Query(value = "SELECT COUNT(*) FROM catalog_outbox_events WHERE published_at IS NULL", nativeQuery = true)
    long countUnpublished();

    /** 미발행 행 가운데 가장 많이 실패한 횟수. 한 행만 계속 실패하는 것(독이 든 메시지)을 드러낸다. */
    @Query(value = "SELECT COALESCE(MAX(publish_attempts), 0) FROM catalog_outbox_events WHERE published_at IS NULL", nativeQuery = true)
    int maxUnpublishedAttempts();
}
