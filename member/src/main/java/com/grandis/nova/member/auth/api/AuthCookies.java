package com.grandis.nova.member.auth.api;

import com.grandis.nova.common.security.Role;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰 쿠키. secure 는 설정값이다. 로컬 http 프로파일만 false.
 * Path 를 재발급·로그아웃 경로로 좁혀 다른 API 요청에는 쿠키가 실리지 않게 한다 — 리프레시 쿠키를 보는 핸들러가 둘뿐이 된다(노출면).
 * SameSite=Strict: 프론트와 API 가 한 도메인이고, 이 쿠키는 같은 사이트 XHR 의 재발급·로그아웃에만 쓰여 최상위 이동에 실릴 일이 없다.
 * OWASP 는 Strict 를 선호하고 잃는 것이 없어 Strict 다.
 *
 * 회원과 관리자는 쿠키 이름이 다르다. 한 브라우저에서 둘을 같이 쓰면 같은 이름이 서로 덮어써 먼저 로그인한 쪽의 리프레시가
 * 사라졌다. 이름을 가르면 둘이 공존한다. Path·속성은 같다.
 */
@Component
public class AuthCookies {

    public static final String REFRESH_TOKEN = "refresh_token";
    public static final String ADMIN_REFRESH_TOKEN = "admin_refresh_token";
    public static final String PATH = "/api/v1/session";

    @ConfigurationProperties("auth.cookie")
    public record Settings(Boolean secure) {
        public Settings {
            if (secure == null) {
                secure = true;
            }
        }
    }

    private final Settings settings;

    public AuthCookies(Settings settings) {
        this.settings = settings;
    }

    /**
     * 쿠키 값을 **원문 그대로** 읽는다. `@CookieValue` 는 값을 URL 디코딩하는데, 잘못된 퍼센트 시퀀스(`%%%`)가 오면 컨트롤러 전에
     * IllegalArgumentException 이 나 500 이 됐다(실측, 2026-09-21). JWT 는 base64url 이라 디코딩할 것이 없다. 없거나 비면 null.
     */
    public static String raw(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }

    public static String nameFor(Role role) {
        return role == Role.ADMIN ? ADMIN_REFRESH_TOKEN : REFRESH_TOKEN;
    }

    public ResponseCookie refresh(String token, Duration maxAge, Role role) {
        return ResponseCookie.from(nameFor(role), token)
                .httpOnly(true).secure(settings.secure()).sameSite("Strict").path(PATH).maxAge(maxAge).build();
    }

    public ResponseCookie expiredRefresh(Role role) {
        return ResponseCookie.from(nameFor(role), "")
                .httpOnly(true).secure(settings.secure()).sameSite("Strict").path(PATH).maxAge(Duration.ZERO).build();
    }
}
