package com.grandis.nova.preorder.deadletter.domain;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.grandis.nova.preorder.deadletter.domain.DeadLetterStatus.OPEN;
import static com.grandis.nova.preorder.deadletter.domain.DeadLetterStatus.REDRIVEN;
import static com.grandis.nova.preorder.deadletter.domain.DeadLetterStatus.REDRIVING;

/** 상태 전이는 모두 현재 상태를 조건으로 한 UPDATE 다. 영향 행이 0 이면 그 사이 다른 쪽이 바꾼 것이다. */
public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, UUID> {

    /** 요약 칸. 원문(body)은 빼고 읽는다. */
    String SUMMARY_COLUMNS = """
            d.id as id, d.messageId as messageId, d.eventId as eventId, d.eventType as eventType,
            d.preorderId as preorderId, d.customerId as customerId, d.failureReason as failureReason,
            d.receiveCount as receiveCount, d.status as status, d.redriveStartedAt as redriveStartedAt,
            d.redrivenFromId as redrivenFromId, d.sentAt as sentAt, d.createdAt as createdAt, d.updatedAt as updatedAt
            """;

    /** 요약 조건. 비운 조건은 거르지 않는다. 기간은 쌓인 시각 [from, to). */
    String SEARCH_CONDITIONS = """
             where (:status is null or d.status = :status)
               and (:eventType is null or d.eventType = :eventType)
               and (:failureReason is null or d.failureReason = :failureReason)
               and (:preorderId is null or d.preorderId = :preorderId)
               and (:customerId is null or d.customerId = :customerId)
               and (:from is null or d.createdAt >= :from)
               and (:to is null or d.createdAt < :to)
            """;

    boolean existsBySourceQueueAndMessageId(String sourceQueue, String messageId);

    /** 관리자 목록. 정렬은 pageable 로 준다. */
    @Query(value = "select " + SUMMARY_COLUMNS + " from DeadLetterEvent d" + SEARCH_CONDITIONS,
            countQuery = "select count(d) from DeadLetterEvent d" + SEARCH_CONDITIONS)
    Page<DeadLetterSummary> search(@Param("status") DeadLetterStatus status, @Param("eventType") String eventType,
                                   @Param("failureReason") FailureReason failureReason,
                                   @Param("preorderId") UUID preorderId, @Param("customerId") UUID customerId,
                                   @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    @Query("select " + SUMMARY_COLUMNS + " from DeadLetterEvent d where d.id in :ids")
    List<DeadLetterSummary> findSummaries(@Param("ids") Collection<UUID> ids);

    @Query("select d.status from DeadLetterEvent d where d.id = :id")
    Optional<DeadLetterStatus> findStatusById(@Param("id") UUID id);

    /** 되돌리기 선점. OPEN 이거나, 보내다 죽어 staleBefore 전부터 REDRIVING 인 행만. */
    default int claimRedrive(UUID id, String by, Instant now, Instant staleBefore) {
        return claim(id, by, now, staleBefore, OPEN, REDRIVING);
    }

    /** 보냄 기록. 내가 선점한 REDRIVING 일 때만(처리가 먼저 끝나 결과가 났으면 0). */
    default int markRedriven(UUID id, Instant startedAt, Instant now) {
        return finishOwnRedrive(id, startedAt, REDRIVING, REDRIVEN, now);
    }

    /**
     * 되돌린 메시지의 결과(SUCCEEDED · REDRIVE_FAILED). 보냄 기록보다 처리가 먼저 끝날 수 있어 REDRIVING 에서도 바꾸고,
     * 그때는 결과 시각을 보낸 시각으로 채운다.
     */
    default int markOutcome(UUID id, DeadLetterStatus outcome, Instant now) {
        return outcome(id, outcome, now, List.of(REDRIVING, REDRIVEN));
    }

    default int discard(UUID id, String by, String note, Instant now) {
        return discard(id, by, note, now, OPEN, DeadLetterStatus.DISCARDED);
    }

    /** 일괄 되돌리기 후보. 되돌리기를 기다리는 행(OPEN · 오래된 REDRIVING)을 쌓인 순으로. eventType 은 비우면 거르지 않는다. */
    default List<UUID> findWaitingIds(String eventType, FailureReason failureReason, Instant staleBefore, int limit) {
        return findWaitingIds(OPEN, REDRIVING, staleBefore, eventType, failureReason, Limit.of(limit));
    }

    default long countWaiting(Instant staleBefore) {
        return countWaiting(OPEN, REDRIVING, staleBefore);
    }

    default Optional<Instant> findOldestWaitingCreatedAt(Instant staleBefore) {
        return findOldestWaitingCreatedAt(OPEN, REDRIVING, staleBefore);
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update DeadLetterEvent d
               set d.status = :redriving, d.redriveRequestedBy = :by, d.redriveStartedAt = :now, d.updatedAt = :now
             where d.id = :id
               and (d.status = :open or (d.status = :redriving and d.redriveStartedAt < :staleBefore))""")
    int claim(@Param("id") UUID id, @Param("by") String by, @Param("now") Instant now,
              @Param("staleBefore") Instant staleBefore, @Param("open") DeadLetterStatus open,
              @Param("redriving") DeadLetterStatus redriving);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update DeadLetterEvent d
               set d.status = :next, d.redrivenAt = :now, d.updatedAt = :now
             where d.id = :id and d.redriveStartedAt = :startedAt and d.status = :current""")
    int finishOwnRedrive(@Param("id") UUID id, @Param("startedAt") Instant startedAt,
                         @Param("current") DeadLetterStatus current, @Param("next") DeadLetterStatus next,
                         @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update DeadLetterEvent d
               set d.status = :outcome, d.outcomeAt = :now, d.redrivenAt = coalesce(d.redrivenAt, :now),
                   d.updatedAt = :now
             where d.id = :id and d.status in :from""")
    int outcome(@Param("id") UUID id, @Param("outcome") DeadLetterStatus outcome, @Param("now") Instant now,
                @Param("from") Collection<DeadLetterStatus> from);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update DeadLetterEvent d
               set d.status = :discarded, d.discardedBy = :by, d.discardedAt = :now, d.discardNote = :note,
                   d.updatedAt = :now
             where d.id = :id and d.status = :open""")
    int discard(@Param("id") UUID id, @Param("by") String by, @Param("note") String note, @Param("now") Instant now,
                @Param("open") DeadLetterStatus open, @Param("discarded") DeadLetterStatus discarded);

    @Query("""
            select d.id from DeadLetterEvent d
             where (d.status = :open or (d.status = :redriving and d.redriveStartedAt < :staleBefore))
               and (:eventType is null or d.eventType = :eventType)
               and d.failureReason = :failureReason
             order by d.createdAt, d.id""")
    List<UUID> findWaitingIds(@Param("open") DeadLetterStatus open, @Param("redriving") DeadLetterStatus redriving,
                              @Param("staleBefore") Instant staleBefore, @Param("eventType") String eventType,
                              @Param("failureReason") FailureReason failureReason, Limit limit);

    @Query("""
            select count(d) from DeadLetterEvent d
             where d.status = :open or (d.status = :redriving and d.redriveStartedAt < :staleBefore)""")
    long countWaiting(@Param("open") DeadLetterStatus open, @Param("redriving") DeadLetterStatus redriving,
                      @Param("staleBefore") Instant staleBefore);

    @Query("""
            select min(d.createdAt) from DeadLetterEvent d
             where d.status = :open or (d.status = :redriving and d.redriveStartedAt < :staleBefore)""")
    Optional<Instant> findOldestWaitingCreatedAt(@Param("open") DeadLetterStatus open,
                                                 @Param("redriving") DeadLetterStatus redriving,
                                                 @Param("staleBefore") Instant staleBefore);
}
