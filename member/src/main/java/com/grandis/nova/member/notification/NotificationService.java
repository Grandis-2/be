package com.grandis.nova.member.notification;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.CursorPage;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 내 알림 조회 · 읽음 표시. 모든 조회 · 변경이 회원 id 조건을 달고 있어 남의 알림은 없는 것과 같다. */
@Service
class NotificationService {

    private final NotificationRepository notifications;
    private final Clock clock;

    NotificationService(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    /** 최신순. 한 개 더 읽어 다음 쪽이 있는지 가른다 — 총계(COUNT)는 내지 않는다. */
    @Transactional(readOnly = true)
    CursorPage<NotificationResponse> list(UUID customerId, String cursor, int size) {
        NotificationCursor after = NotificationCursor.decode(cursor);
        List<Notification> found = new ArrayList<>(after == null
                ? notifications.findNewest(customerId, Limit.of(size + 1))
                : notifications.findOlder(customerId, after.occurredAt(), after.id(), Limit.of(size + 1)));
        if (found.size() <= size) {
            return CursorPage.last(found.stream().map(NotificationResponse::of).toList());
        }
        List<Notification> page = found.subList(0, size);
        return CursorPage.of(page.stream().map(NotificationResponse::of).toList(), NotificationCursor.encode(page.getLast()));
    }

    long unreadCount(UUID customerId) {
        return notifications.countByCustomerIdAndReadAtIsNull(customerId);
    }

    /**
     * 이미 읽었으면 그대로 둔다(처음 읽은 시각 유지). 남의 알림이나 없는 id 는 같은 404 다 — 남의 알림이 있는지 드러내지 않는다.
     * 바꾼 행이 없을 때만 있는지 확인한다 — 안 읽은 알림을 누르는 흔한 경우는 UPDATE 한 번이다.
     */
    @Transactional
    void markRead(UUID customerId, UUID notificationId) {
        if (notifications.markRead(notificationId, customerId, now()) == 0
                && !notifications.existsByIdAndCustomerId(notificationId, customerId)) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "알림을 찾을 수 없습니다.");
        }
    }

    @Transactional
    void markAllRead(UUID customerId) {
        notifications.markAllRead(customerId, now());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
