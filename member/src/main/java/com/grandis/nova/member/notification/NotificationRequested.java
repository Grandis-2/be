package com.grandis.nova.member.notification;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * NOTIFICATION_REQUESTED 의 payload(contracts/notification-events.md). 칸이 비었거나 칸 길이를 넘으면 풀 때 실패시킨다(계약 위반 — DLQ 로 바로 간다).
 * 길이는 코드포인트로 센다 — MySQL utf8mb4 varchar(N) 은 문자 N개다(CodePointSize 와 같은 자). 값은 고치지 않고 그대로 담는다.
 * kind 는 값을 보지 않는다 — 새 알림 종류를 member 배포 없이 더할 수 있게.
 *
 * @param recipientIds 받는 회원 1~1,000명. 겹치면 거절한다 — 보내는 쪽이 같은 회원을 두 번 담았다면 묶는 코드가 틀린 것이다
 */
record NotificationRequested(List<UUID> recipientIds, String kind, String title, String body, Target target) {

    static final int MAX_RECIPIENTS = 1000;

    NotificationRequested {
        if (recipientIds == null || recipientIds.isEmpty() || recipientIds.size() > MAX_RECIPIENTS) {
            throw new IllegalArgumentException("recipientIds 는 1~" + MAX_RECIPIENTS + "명이다");
        }
        if (recipientIds.stream().anyMatch(Objects::isNull) || new HashSet<>(recipientIds).size() != recipientIds.size()) {
            throw new IllegalArgumentException("recipientIds 에 빈 값이나 같은 회원이 있다");
        }
        recipientIds = List.copyOf(recipientIds);
        require("kind", kind, 50);
        require("title", title, 100);
        require("body", body, 500);
        if (target == null) {
            throw new IllegalArgumentException("target 이 없다");
        }
    }

    /** 누르면 갈 곳. 주소는 프론트가 type · id 로 만든다. */
    record Target(String type, String id) {

        Target {
            require("target.type", type, 30);
            require("target.id", id, 100);
        }
    }

    private static void require(String name, String value, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 이 비었다");
        }
        if (value.codePointCount(0, value.length()) > max) {
            throw new IllegalArgumentException(name + " 이 " + max + "자를 넘는다");
        }
    }
}
