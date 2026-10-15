package com.grandis.nova.order.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 회원 알림(contracts/notification-events.md). member 가 받아 받는 회원마다 알림 표에 한 행씩 저장한다. member 에는 응모를 읽을 길이 없어
 * 화면에 보일 값(문구 · 갈 곳)을 여기서 완성해 싣는다. 계약은 한 메시지에 1~1,000명이지만 order 의 알림(당첨)은 응모마다 한 명이다.
 *
 * 길이를 넘거나 빈 값이면 member 가 거절한다(DLQ). 그때는 order 가 이미 커밋해 되돌릴 수 없으므로 만들 때 막는다.
 *
 * @param aggregateId  알림이 생긴 원인의 id(종류는 kind 가 정한다). 봉투로만 나가고 payload 에는 싣지 않는다
 * @param recipientIds 받는 회원 id 1~1,000개, 겹치지 않게
 * @param target       누르면 갈 곳. 주소는 프론트가 만든다
 */
public record NotificationRequested(@JsonIgnore UUID aggregateId, List<UUID> recipientIds, Kind kind, String title, String body,
                                    Target target)
        implements OutboxMessage {

    static final int MAX_RECIPIENTS = 1000;

    public NotificationRequested {
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(recipientIds, "recipientIds");
        recipientIds = List.copyOf(recipientIds);   // null 원소는 여기서 NPE
        if (recipientIds.isEmpty() || recipientIds.size() > MAX_RECIPIENTS || new HashSet<>(recipientIds).size() != recipientIds.size()) {
            throw new IllegalArgumentException("받는 회원은 겹치지 않게 1~" + MAX_RECIPIENTS + "명이다: kind=" + kind);
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        // member 와 같은 자로 센다(코드 포인트 = MySQL utf8mb4 varchar 의 문자 수) — 이모지 같은 보조 평면 문자가 두 칸으로 세지지 않게
        if (title == null || title.isBlank() || title.codePointCount(0, title.length()) > 100
                || body == null || body.isBlank() || body.codePointCount(0, body.length()) > 500) {
            throw new IllegalArgumentException("제목은 1~100자, 본문은 1~500자다: kind=" + kind);
        }
    }

    /** 드로우 당첨 — 당첨 응모 하나에 한 건. 낙첨에는 보내지 않는다. */
    public static NotificationRequested drawWon(UUID entryId, UUID customerId, UUID drawId) {
        Kind kind = Kind.DRAW_WON;
        return new NotificationRequested(entryId, List.of(customerId), kind, kind.title, kind.body,
                new Target(TargetType.DRAW, drawId.toString()));
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.NOTIFICATION_REQUESTED;
    }

    @Override
    public AggregateType aggregateType() {
        return kind.aggregateType;
    }

    /** order 가 보내는 알림 종류 · 원인 · 고정 문구. 계약의 표와 같아야 한다. */
    public enum Kind {
        DRAW_WON(AggregateType.DRAW_ENTRY, "럭키 드로우 당첨", "축하해요! 럭키 드로우에 당첨됐어요.");

        private final AggregateType aggregateType;
        private final String title;
        private final String body;

        Kind(AggregateType aggregateType, String title, String body) {
            this.aggregateType = aggregateType;
            this.title = title;
            this.body = body;
        }
    }

    public enum TargetType {
        /** 회차 id — 드로우 상세 · 내 응모 결과 */
        DRAW
    }

    public record Target(TargetType type, String id) {

        public Target {
            Objects.requireNonNull(type, "type");
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("갈 곳의 id 가 없다: type=" + type);
            }
        }
    }
}
