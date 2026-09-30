package com.grandis.nova.order.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 기록(OutboxWriter)과 발행(OutboxPublisher · OutboxRelay)만 쓴다. package-private 이라 업무 코드는 이 저장소로
 * 아웃박스를 적거나 발행 칸을 바꿀 수 없다.
 *
 * common:outbox 이전 시: preorder 의 저장소(public)와 메서드 · 쿼리가 같다. 한 벌로 합친다.
 */
interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** 발행 완료 표시. 이미 채워졌으면 바꾸지 않는다. */
    @Transactional
    @Modifying
    @Query("update OutboxEvent e set e.publishedAt = :now where e.id = :id and e.publishedAt is null")
    int markPublished(@Param("id") Long id, @Param("now") Instant now);

    /** 발행 실패 횟수를 올린다. */
    @Transactional
    @Modifying
    @Query("update OutboxEvent e set e.publishAttempts = e.publishAttempts + 1 where e.id = :id")
    int recordFailure(@Param("id") Long id);

    /**
     * 릴레이가 보낼 행을 잠가 가져온다. 다른 인스턴스가 잠근 행은 건너뛴다(SKIP LOCKED).
     * 같은 표를 preorder 릴레이도 읽으므로 event_type 으로 자기 행만 집는다.
     */
    @Query(value = """
            SELECT * FROM outbox_events
             WHERE published_at IS NULL AND created_at <= :createdBefore AND event_type IN (:eventTypes)
             ORDER BY id
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockUnpublished(@Param("createdBefore") Instant createdBefore,
                                      @Param("eventTypes") Collection<String> eventTypes, @Param("limit") int limit);
}
