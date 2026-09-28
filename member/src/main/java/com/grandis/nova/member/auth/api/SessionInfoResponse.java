package com.grandis.nova.member.auth.api;

import com.grandis.nova.common.security.Role;

/** api-spec F-X-01 `GET /session` 응답 data: `{displayName, role}`. 토큰은 없다. */
public record SessionInfoResponse(String displayName, Role role) {
}
