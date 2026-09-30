package com.grandis.nova.payment.config;

import com.grandis.nova.payment.client.toss.TossAuthorization;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.http.client.autoconfigure.HttpClientProperties;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;

/**
 * 토스 호출의 시간 예산과 주소를 기동 때 확인한다. 값은 저장소 밖 설정(application.yml)에만 있어 빠져도 오류가 나지 않는다 —
 * 그런데 JDK 구현은 read-timeout 이 없으면 응답을 기한 없이 기다리고(Spring 7 JdkClientHttpRequest), 멈춘 호출 스레드가 쌓인다.
 * 시크릿 키(TossProperties)처럼 없으면 뜨지 않게 한다. TransactionIsolationVerifier 와 같은 결의 검증이다.
 *
 * 확인: spring.http.serviceclient.toss 의 base-url · connect-timeout · read-timeout 이 있고, 연결 + 읽기 ≤ 리스(D12),
 * 리다이렉트를 따라가지 않는다(POST 가 301/302 로 GET 이 되거나 다른 곳으로 가지 않게), 주소가 https 다(loopback 제외 —
 * http 면 첫 요청의 Basic 인증, 곧 시크릿 키가 평문으로 나간다).
 *
 * 값은 토스 그룹에 직접 적어야 한다. 스프링 부트는 전역 spring.http.clients.* 도 대체값으로 쓰지만 여기서는 보지 않는다 —
 * 돈이 걸린 호출의 예산이 다른 클라이언트 설정에 딸려 바뀌지 않게 막는 쪽(기동 실패)으로 둔다.
 */
final class TossHttpSettingsVerifier {

    static final String PREFIX = "spring.http.serviceclient." + TossAuthorization.GROUP;
    /**
     * 결제 시도 리스(D12, 70초). 호출 한 번이 이보다 길면 리스가 끝난 뒤 복구 워커가 같은 요청을 또 보낸다.
     * 리스 값의 주인은 결제 원장(NV-99)이다 — 클라이언트 설정이 원장을 모르게 여기 따로 두고, 둘이 어긋나면 NV-101 이 맞춘다.
     */
    static final Duration LEASE = Duration.ofSeconds(70);

    private TossHttpSettingsVerifier() {
    }

    static void verify(Environment environment) {
        HttpClientProperties toss = Binder.get(environment).bind(PREFIX, HttpClientProperties.class)
                .orElseThrow(() -> new IllegalStateException(PREFIX + " 설정이 없다 — base-url · connect-timeout · read-timeout 필수"));
        require(toss.getBaseUrl() != null && !toss.getBaseUrl().isBlank(), "base-url 이 필요하다");
        require(isHttpsOrLoopback(toss.getBaseUrl()), "base-url 은 https 여야 한다(시크릿 키가 Basic 인증으로 실린다)");
        Duration connect = toss.getConnectTimeout();
        Duration read = toss.getReadTimeout();
        require(connect != null && connect.isPositive(), "connect-timeout 이 필요하다(0보다 큰 값)");
        require(read != null && read.isPositive(), "read-timeout 이 필요하다(0보다 큰 값) — 없으면 응답을 기한 없이 기다린다");
        require(connect.plus(read).compareTo(LEASE) <= 0,
                "connect-timeout + read-timeout(" + connect.plus(read).toSeconds() + "s) 이 결제 시도 리스("
                        + LEASE.toSeconds() + "s) 를 넘는다");
        require(toss.getRedirects() == HttpRedirects.DONT_FOLLOW, "redirects: dont-follow 여야 한다");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(PREFIX + ": " + message + " (토스 그룹에 직접 적는다 — 전역 spring.http.clients.* 는 보지 않는다)");
        }
    }

    /** https 이거나, 테스트 · 로컬 대역처럼 이 호스트 안에서만 오가는 loopback 주소. */
    private static boolean isHttpsOrLoopback(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return uri.getHost() != null;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return false;
        }
        return uri.getHost().equalsIgnoreCase("localhost") || isLoopbackLiteral(uri.getHost());
    }

    /**
     * IP 리터럴이고 loopback(127.0.0.0/8 · ::1)인가. 이름을 문자열 접두어로 보지 않는다 — "127.example.com" 은 DNS 에 따라 외부
     * 서버가 되고, 거기로 Basic 인증(시크릿 키)이 평문으로 나간다. InetAddress.ofLiteral 은 리터럴만 읽고 DNS 를 찾지 않는다.
     */
    private static boolean isLoopbackLiteral(String host) {
        String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try {
            return InetAddress.ofLiteral(literal).isLoopbackAddress();
        } catch (IllegalArgumentException notLiteral) {
            return false;
        }
    }
}
