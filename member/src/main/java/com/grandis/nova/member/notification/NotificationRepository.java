package com.grandis.nova.member.notification;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 내 알림 조회 · 읽음 표시. 모두 customer_id 로 시작한다 — 목록은 ix_notification_customer_occurred, 안 읽은 수는 ix_notification_customer_unread. */
interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("""
            SELECT n FROM Notification n
             WHERE n.customerId = :customerId
             ORDER BY n.occurredAt DESC, n.id DESC""")
    List<Notification> findNewest(@Param("customerId") UUID customerId, Limit limit);

    /** 커서보다 뒤(더 오래된 것). 정렬이 occurred_at · id 내림차순이라 둘을 함께 본다. */
    @Query("""
            SELECT n FROM Notification n
             WHERE n.customerId = :customerId
               AND (n.occurredAt < :occurredAt OR (n.occurredAt = :occurredAt AND n.id < :id))
             ORDER BY n.occurredAt DESC, n.id DESC""")
    List<Notification> findOlder(@Param("customerId") UUID customerId, @Param("occurredAt") Instant occurredAt, @Param("id") UUID id,
                                 Limit limit);

    long countByCustomerIdAndReadAtIsNull(UUID customerId);

    boolean existsByIdAndCustomerId(UUID id, UUID customerId);

    /** @return 이번에 읽음으로 바꾼 행 수(0 또는 1). 이미 읽었으면 0 — 처음 읽은 시각을 덮지 않는다 */
    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :readAt WHERE n.id = :id AND n.customerId = :customerId AND n.readAt IS NULL")
    int markRead(@Param("id") UUID id, @Param("customerId") UUID customerId, @Param("readAt") Instant readAt);

    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :readAt WHERE n.customerId = :customerId AND n.readAt IS NULL")
    int markAllRead(@Param("customerId") UUID customerId, @Param("readAt") Instant readAt);
}
