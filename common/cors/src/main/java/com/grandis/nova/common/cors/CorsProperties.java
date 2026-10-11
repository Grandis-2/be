package com.grandis.nova.common.cors;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 브라우저 호출을 허용할 프론트 오리진. 기본값은 CorsDefaults 가 깔고, 설정에 적으면 목록이 통째로 바뀐다(더해지지 않는다).
 * 리프레시 쿠키 때문에 자격 증명을 허용하므로 와일드카드는 쓸 수 없다 — 정확한 오리진만 받고 모양이 틀리면 기동 실패다.
 *
 * @param allowedOrigins scheme://host[:port]. 경로 · 끝의 '/' 없이
 */
@ConfigurationProperties("nova.cors")
record CorsProperties(List<String> allowedOrigins) {

    private static final Pattern ORIGIN = Pattern.compile("(https?)://[A-Za-z0-9.-]+(?::([1-9]\\d{0,4}))?");
    private static final int MAX_PORT = 65_535;

    CorsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("nova.cors.allowed-origins 가 비어 있다");
        }
        for (String origin : allowedOrigins) {
            if (!isBrowserOrigin(origin)) {
                throw new IllegalArgumentException("nova.cors.allowed-origins 는 브라우저가 보내는 모양(scheme://host[:port])이어야 한다"
                        + "(경로 · 끝 '/' · 와일드카드 · 기본 포트 · 범위 밖 포트 불가): " + origin);
            }
        }
        allowedOrigins = List.copyOf(allowedOrigins);
    }

    /** 이 목록에 more 를 더한 것(순서 유지 · 중복 제거). 더한 값도 같은 검사를 거친다. */
    CorsProperties including(List<String> more) {
        Set<String> merged = new LinkedHashSet<>(allowedOrigins);
        merged.addAll(more);
        return new CorsProperties(List.copyOf(merged));
    }

    /** 브라우저는 기본 포트(https 443 · http 80)를 빼고 보낸다 — 붙여 적으면 어떤 요청과도 맞지 않는다. */
    private static boolean isBrowserOrigin(String origin) {
        if (origin == null) {
            return false;
        }
        Matcher matcher = ORIGIN.matcher(origin);
        if (!matcher.matches()) {
            return false;
        }
        if (matcher.group(2) == null) {
            return true;
        }
        int port = Integer.parseInt(matcher.group(2));
        int defaultPort = matcher.group(1).equals("https") ? 443 : 80;
        return port <= MAX_PORT && port != defaultPort;
    }
}
