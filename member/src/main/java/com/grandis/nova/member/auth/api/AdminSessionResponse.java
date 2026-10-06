package com.grandis.nova.member.auth.api;

import com.grandis.nova.common.security.Role;

/** api-spec F-X-01 `POST /admin/session` 응답 data: `{sessionToken, role}`. 관리자는 표시 이름이 없다. */
public record AdminSessionResponse(String sessionToken, Role role) {
}
