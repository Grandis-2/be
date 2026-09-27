package com.grandis.nova.common.security;

import java.time.Instant;
import java.util.UUID;

/**
 * 파싱된 토큰의 내용. 토큰에 넣는 클레임 전부이고 그 밖의 것은 없다.
 * 프로필(닉네임 등)은 토큰에 넣지 않는다 — 넣으면 값이 바뀔 때 토큰을 교체하는 장치가 따라온다.
 *
 * @param subject   USER 면 customers.id 의 십진 문자열, ADMIN 이면 "admin"
 * @param sessionId 로그인 세션(sid). 액세스와 리프레시가 같은 값을 갖는다. 로그아웃은 이 단위로 폐기한다
 * @param tokenId   토큰 한 장(jti). 리프레시 회전의 비교 대상
 * @param issuedAt  회원 단위 not-before(auth:nbf) 와 비교하는 기준. JWT 의 iat·exp 는 epoch 초라 밀리초 아래가 잘려 있다.
 *                  nbf 비교도 초 단위로 한다. 밀리초로 다루면 같은 초 안의 발급이 nbf 앞뒤로 갈린다
 */
public record TokenClaims(
        String subject,
        UUID sessionId,
        UUID tokenId,
        Role role,
        TokenType type,
        Instant issuedAt,
        Instant expiresAt
) {
}
