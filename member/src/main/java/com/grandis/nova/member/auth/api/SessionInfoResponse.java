package com.grandis.nova.member.auth.api;

import com.grandis.nova.common.security.Role;

/**
 * api-spec F-X-01 `GET /session` 응답 data. 토큰은 없다.
 *
 * `profileComplete` 는 로그인 응답과 같은 값이다 — 새로고침으로 돌아온 화면도 같은 판정을 할 수 있어야 한다.
 * 로그인 응답에만 실으면 새로고침한 사용자가 입력을 건너뛴다.
 */
public record SessionInfoResponse(String displayName, Role role, boolean profileComplete) {
}
