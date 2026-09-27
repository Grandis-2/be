package com.grandis.nova.member.auth.application;

/**
 * 리프레시 토큰을 발급·회전한 요청의 흔적. `refresh_tokens.client_ip`·`user_agent` 에 그대로 들어간다.
 * 인증 판정에는 쓰지 않는다 — IP 는 정상적으로도 바뀌고 User-Agent 는 위조된다. "로그인된 기기" 표시와 사고 조사용이다.
 * 칸 길이(45 · 255)를 넘으면 잘라서 담는다. 길이 때문에 로그인이 실패하는 일은 없어야 한다.
 */
public record ClientInfo(String ip, String userAgent) {

    private static final int IP_MAX = 45;            // IPv6 최대 표기
    private static final int USER_AGENT_MAX = 255;

    public static final ClientInfo UNKNOWN = new ClientInfo(null, null);

    public ClientInfo {
        ip = trim(ip, IP_MAX);
        userAgent = trim(userAgent, USER_AGENT_MAX);
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        return v.length() <= max ? v : v.substring(0, max);
    }
}
