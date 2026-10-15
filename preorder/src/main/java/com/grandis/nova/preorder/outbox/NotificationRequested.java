package com.grandis.nova.preorder.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 회원 알림 한 건 — 같은 문구를 받는 회원 1~1,000명. member 가 받아 회원마다 저장한다(contracts/notification-events.md).
 * member 는 예약을 읽을 수 없어, 다른 메시지와 달리 화면에 보일 문구와 이동할 곳까지 완성해 싣는다.
 * 문구는 계약 표의 문구다. aggregate 는 봉투로만 나가는 추적용이다.
 *
 * @param recipientIds 받는 회원. 1~{@value #MAX_RECIPIENTS}명, 겹치지 않게 — member 가 어긋난 메시지를 DLQ 로 보내므로 여기서 막는다
 */
public record NotificationRequested(@JsonIgnore AggregateType aggregateType, @JsonIgnore UUID aggregateId,
                                    List<UUID> recipientIds, Kind kind, String title, String body, Target target)
        implements OutboxMessage {

    /** 한 메시지의 최대 수신자. 1,000명이면 본문이 약 40KB 로 SQS 한도(256KB) 안이다. */
    public static final int MAX_RECIPIENTS = 1000;

    public NotificationRequested {
        recipientIds = List.copyOf(Objects.requireNonNull(recipientIds, "recipientIds"));
        if (recipientIds.isEmpty() || recipientIds.size() > MAX_RECIPIENTS) {
            throw new IllegalArgumentException("recipientIds 는 1~" + MAX_RECIPIENTS + "명: " + recipientIds.size());
        }
        if (new HashSet<>(recipientIds).size() != recipientIds.size()) {
            throw new IllegalArgumentException("recipientIds 가 겹친다");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
    }

    /** 예약이 외부 등록을 마쳐 REGISTERED 가 됐다. 회원은 공개 토큰으로 내 예약 상세를 연다. */
    public static NotificationRequested preorderRegistered(UUID preorderInternalId, String preorderToken,
                                                           UUID customerId) {
        return new NotificationRequested(AggregateType.PREORDER, preorderInternalId, List.of(customerId),
                Kind.PREORDER_REGISTERED, "사전예약 등록 완료", "사전예약 등록이 완료됐어요. 24시간 안에 결제해 주세요.",
                new Target(TargetType.PREORDER, preorderToken));
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.NOTIFICATION_REQUESTED;
    }

    public enum Kind {
        PREORDER_REGISTERED
    }

    public enum TargetType {
        PREORDER
    }

    /** 누르면 갈 곳. 주소는 프론트가 만든다 — 주소가 바뀌어도 저장된 알림이 깨지지 않게. */
    public record Target(TargetType type, String id) {
    }
}
