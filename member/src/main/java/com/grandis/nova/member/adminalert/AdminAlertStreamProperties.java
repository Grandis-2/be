package com.grandis.nova.member.adminalert;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 관리자 알림 실시간 연결(GET /api/v1/admin/notifications/stream) 설정. 기본값이 운영값이라 인프라에 적지 않아도 된다.
 * 뜻은 회원 알림의 실시간 연결 설정과 같다. 관리자는 계정 하나라 연결이 탭 수만큼이고, 그래서 상한을 작게 둔다.
 *
 * @param maxPerPrincipal 한 관리자 계정(토큰 sub)이 이 인스턴스에 여는 연결 수. 넘으면 가장 오래된 연결을 닫는다
 * @param maxConnections  이 인스턴스의 연결 상한. 넘으면 503 — 화면은 주기 조회로 돌아간다
 * @param maxPending      연결 하나가 쓰지 못하고 쌓아 둘 사건 수. 넘으면 닫는다 — 화면은 다시 연결해 목록을 새로 읽는다
 */
@ConfigurationProperties("member.admin-alert.stream")
record AdminAlertStreamProperties(
        @DefaultValue("25s") Duration heartbeat,
        @DefaultValue("30m") Duration maxLifetime,
        @DefaultValue("20") int maxPerPrincipal,
        @DefaultValue("100") int maxConnections,
        @DefaultValue("1000") int maxPending,
        @DefaultValue("2s") Duration tailInterval,
        @DefaultValue("10s") Duration overlap
) {

    AdminAlertStreamProperties {
        if (!heartbeat.isPositive() || !maxLifetime.isPositive() || !tailInterval.isPositive() || !overlap.isPositive()) {
            throw new IllegalArgumentException("member.admin-alert.stream.heartbeat · max-lifetime · tail-interval · overlap 은 0 보다 커야 한다");
        }
        if (maxPerPrincipal <= 0 || maxConnections <= 0 || maxPending <= 0) {
            throw new IllegalArgumentException("member.admin-alert.stream.max-per-principal · max-connections · max-pending 은 0 보다 커야 한다");
        }
    }
}
