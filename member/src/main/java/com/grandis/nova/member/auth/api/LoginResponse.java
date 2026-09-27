package com.grandis.nova.member.auth.api;

import com.grandis.nova.common.security.Role;

/**
 * api-spec F-X-01 `POST /auth/kakao/callback`·`POST /session/refresh` 응답 data. 회원 내부 PK·카카오 식별자·카카오 토큰은 넣지 않는다.
 * sessionToken 은 JWT 액세스 토큰이다(값만 JWT, 이름은 계약). 리프레시는 본문이 아니라 쿠키로 간다.
 * 응답마다 record 를 따로 둔다 — 계약이 셋으로 다르고, 하나를 공유하면 계약에 없는 칸이 null 로 붙는다(Jackson 3 기본 inclusion 은 ALWAYS, 실측).
 */
public record LoginResponse(String sessionToken, String displayName, Role role) {
}
