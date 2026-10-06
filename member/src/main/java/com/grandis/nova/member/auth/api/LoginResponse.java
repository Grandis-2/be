package com.grandis.nova.member.auth.api;

import com.grandis.nova.common.security.Role;

/**
 * api-spec F-X-01 `POST /auth/kakao/callback`·`POST /session/refresh` 응답 data. 회원 내부 PK·카카오 식별자·카카오 토큰은 넣지 않는다.
 * sessionToken 은 JWT 액세스 토큰이다(값만 JWT, 이름은 계약). 리프레시는 본문이 아니라 쿠키로 간다.
 * 응답마다 record 를 따로 둔다 — 계약이 셋으로 다르고, 하나를 공유하면 계약에 없는 칸이 null 로 붙는다(Jackson 3 기본 inclusion 은 ALWAYS, 실측).
 *
 * `profileComplete` 가 false 면 화면이 내 정보 입력을 먼저 받아야 한다. 관리자는 입력할 정보가 없어 **항상 true** 다 —
 * null 을 쓰지 않는 이유는 받는 쪽이 `!profileComplete` 한 줄로 끝나게 하기 위해서다(null 이면 그 식이 조용히 반대로 돈다).
 */
public record LoginResponse(String sessionToken, String displayName, Role role, boolean profileComplete) {
}
