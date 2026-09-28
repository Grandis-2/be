package com.grandis.nova.common.security;

/**
 * 컨텍스트에 실리는 인증 주체. 토큰에서 온 두 값뿐이다 — 회원 프로필은 여기 없다(필터가 DB 를 안 읽는다).
 *
 * @param subject USER 면 customers.id 의 십진 문자열, ADMIN 이면 "admin"
 */
public record AuthenticatedPrincipal(String subject, Role role) {

    /** USER 토큰의 회원 PK. ADMIN 에는 없다 — 호출 전에 role 을 봐야 한다. */
    public long customerId() {
        if (role != Role.USER) {
            throw new IllegalStateException("customerId is only defined for USER, was " + role);
        }
        return Long.parseLong(subject);
    }
}
