package com.grandis.nova.common.cors;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 브라우저 호출을 허용할 프론트 오리진. 기본값은 CorsDefaults 가 깔고, 설정에 적으면 목록이 통째로 바뀐다(더해지지 않는다).
 * 리프레시 쿠키 때문에 자격 증명을 허용하므로 와일드카드는 쓸 수 없다 — 정확한 오리진만 받고 모양이 틀리면 기동 실패다.
 *
 * @param allowedOrigins scheme://host[:port]. 경로 · 끝의 '/' 없이
 */
@ConfigurationProperties("nova.cors")
record CorsProperties(List<String> allowedOrigins) {

    private static final Pattern ORIGIN = Pattern.compile("https?://[A-Za-z0-9.-]+(:\\d{1,5})?");

    CorsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("nova.cors.allowed-origins 가 비어 있다");
        }
        for (String origin : allowedOrigins) {
            if (origin == null || !ORIGIN.matcher(origin).matches()) {
                throw new IllegalArgumentException(
                        "nova.cors.allowed-origins 는 scheme://host[:port] 여야 한다(경로 · 끝 '/' · 와일드카드 불가): " + origin);
            }
        }
        allowedOrigins = List.copyOf(allowedOrigins);
    }
}
