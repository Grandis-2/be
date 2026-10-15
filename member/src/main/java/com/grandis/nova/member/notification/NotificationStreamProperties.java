package com.grandis.nova.member.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 안 읽은 수 실시간 연결(GET /api/v1/notifications/stream) 설정. 기본값이 운영값이라 인프라에 적지 않아도 된다.
 *
 * @param heartbeat      연결마다 보내는 SSE 주석 간격. CloudFront 원본 읽기 제한(60초) · ALB 유휴 제한(65초)보다 짧아야 끊기지 않는다
 * @param maxLifetime    연결 하나의 최대 수명. 액세스 토큰이 먼저 만료되면 그때 닫는다 — 화면은 새 토큰으로 다시 연결한다
 * @param maxPerCustomer 회원 하나가 이 인스턴스에 여는 연결 수(탭 · 기기). 넘으면 가장 오래된 연결을 닫는다
 * @param maxConnections 이 인스턴스의 연결 상한. 넘으면 503 — 화면은 주기 조회로 돌아간다
 * @param tailInterval   연결이 있는 인스턴스가 DB 에서 새 알림을 찾고 수를 세어 보내는 간격. 첫 수 · 새 알림의 수가 이만큼 늦게 온다
 * @param overlap        새 알림을 찾을 때 앞 주기의 시작보다 이만큼 앞부터 다시 읽는다. id(v7)를 만든 뒤 커밋까지 이보다 오래 걸린 알림은
 *                       실시간으로 가지 않는다(화면의 안전망 조회가 잡는다) — {@link NotificationTail}
 */
@ConfigurationProperties("member.notification.stream")
record NotificationStreamProperties(
        @DefaultValue("25s") Duration heartbeat,
        @DefaultValue("30m") Duration maxLifetime,
        @DefaultValue("5") int maxPerCustomer,
        @DefaultValue("5000") int maxConnections,
        @DefaultValue("2s") Duration tailInterval,
        @DefaultValue("10s") Duration overlap
) {

    NotificationStreamProperties {
        if (!heartbeat.isPositive() || !maxLifetime.isPositive() || !tailInterval.isPositive() || !overlap.isPositive()) {
            throw new IllegalArgumentException("member.notification.stream.heartbeat · max-lifetime · tail-interval · overlap 은 0 보다 커야 한다");
        }
        if (maxPerCustomer <= 0 || maxConnections <= 0) {
            throw new IllegalArgumentException("member.notification.stream.max-per-customer · max-connections 는 0 보다 커야 한다");
        }
    }
}
