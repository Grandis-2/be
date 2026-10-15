package com.grandis.nova.preorder.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 회원 알림 한 건 — 같은 문구를 받는 회원 1~1,000명. member 가 받아 회원마다 저장한다(contracts/notification-events.md).
 * member 는 예약 · 회차를 읽을 수 없어, 다른 메시지와 달리 화면에 보일 문구와 이동할 곳까지 완성해 싣는다.
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

    /**
     * 예약 취소가 끝나 CANCELED 가 됐다. 문구는 취소 까닭과 결제 여부로 가른다.
     * 결제된 예약은 order 가 환불을 마쳐야(주문 CANCELED) 주문 정리 결과를 보내고 그 뒤에야 CANCELED 가 되므로 "환불됐어요" 다.
     *
     * @param paid 결제 확인을 받은 예약인가
     */
    public static NotificationRequested preorderCanceled(UUID preorderInternalId, String preorderToken,
                                                         UUID customerId, CancelCause cause, boolean paid) {
        String body = switch (cause) {
            case USER -> "사전예약 취소가 완료됐어요.";
            case EXPIRY -> "결제 기한이 지나 사전예약이 취소됐어요.";
            case OTHER -> "사전예약이 취소됐어요.";
        };
        return new NotificationRequested(AggregateType.PREORDER, preorderInternalId, List.of(customerId),
                Kind.PREORDER_CANCELED, "사전예약 취소", paid ? body + " 결제하신 금액은 환불됐어요." : body,
                new Target(TargetType.PREORDER, preorderToken));
    }

    /**
     * 오픈 알림을 신청한 회원에게 회차가 열렸다고 알린다. 회원은 상품 상세로 간다.
     *
     * @param productTitle 상품명 사본. null 이면 상품명 없는 문구로 보낸다(catalog 값을 받기 전 회차)
     */
    public static NotificationRequested preorderOpened(UUID productId, String productTitle, List<UUID> recipientIds) {
        String body = productTitle == null ? "알림 신청하신 사전예약이 시작됐어요."
                : "알림 신청하신 " + productTitle + " 사전예약이 시작됐어요.";
        return new NotificationRequested(AggregateType.PREORDER_CAMPAIGN, productId, recipientIds,
                Kind.PREORDER_OPENED, "사전예약 오픈", body, new Target(TargetType.PRODUCT, productId.toString()));
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.NOTIFICATION_REQUESTED;
    }

    public enum Kind {
        PREORDER_REGISTERED,
        PREORDER_CANCELED,
        PREORDER_OPENED
    }

    /**
     * 취소 알림 문구를 가르는 까닭. 관리자 · 회차 판매 중지 · 알 수 없음은 문구가 같아 OTHER 하나다.
     * 기반 모듈이라 예약 모듈의 CancelReason 을 쓰지 않는다.
     */
    public enum CancelCause {
        USER,
        EXPIRY,
        OTHER
    }

    public enum TargetType {
        PREORDER,
        PRODUCT
    }

    /** 누르면 갈 곳. 주소는 프론트가 만든다 — 주소가 바뀌어도 저장된 알림이 깨지지 않게. */
    public record Target(TargetType type, String id) {
    }
}
