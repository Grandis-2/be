package com.grandis.nova.member.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * 내 알림 목록의 한 항목. 주소 대신 target(type · id)을 준다 — 프론트가 주소를 만들어, 주소가 바뀌어도 저장된 알림이 깨지지 않게.
 *
 * @param message   본문(표의 body)
 * @param read      읽었으면 true
 * @param createdAt 알림이 생긴 시각 — 보낸 쪽 발생 시각(occurred_at)이다. 목록도 이 순서다
 */
public record NotificationResponse(UUID notificationId, String kind, String title, String message, Target target, boolean read,
                                   Instant createdAt) {

    public record Target(String type, String id) {
    }

    static NotificationResponse of(Notification n) {
        return new NotificationResponse(n.getId(), n.getKind(), n.getTitle(), n.getBody(),
                new Target(n.getTargetType(), n.getTargetId()), n.getReadAt() != null, n.getOccurredAt());
    }
}
