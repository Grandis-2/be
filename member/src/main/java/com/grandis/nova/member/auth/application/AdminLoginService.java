package com.grandis.nova.member.auth.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.member.auth.AuthErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 관리자는 회원 테이블 밖 단일 환경변수 계정. username 은 그대로 비교, password 는 bcrypt 해시와 matches.
 * username 이 틀려도 해시 비교를 한 번 하고 나서 실패한다 — 응답 시간으로 username 존재 여부를 흘리지 않는다.
 * 무차별 대입 방어는 컨트롤러가 이 서비스를 부르기 전에 건다 — IP 별 시도 제한(AdminLoginThrottle, 기본 1분 5회 → 429). WAF 가 있어도 겹쳐 둔다
 * (WAF 규칙은 이 저장소 밖이라 코드로 보장되지 않는다). 실패 사유는 응답에 구분하지 않는다(INVALID_CREDENTIALS 하나).
 */
@Service
public class AdminLoginService {

    public static final String ADMIN_SUBJECT = "admin";

    private final AdminProperties properties;
    private final PasswordEncoder encoder;
    private final TokenService tokens;

    public AdminLoginService(AdminProperties properties, PasswordEncoder encoder, TokenService tokens) {
        this.properties = properties;
        this.encoder = encoder;
        this.tokens = tokens;
    }

    public TokenService.IssuedTokens login(String username, String password, ClientInfo client) {
        boolean passwordOk = encoder.matches(password == null ? "" : password, properties.passwordHash());
        boolean usernameOk = properties.username().equals(username);
        if (!(usernameOk && passwordOk)) {
            throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        return tokens.issue(ADMIN_SUBJECT, Role.ADMIN, client);
    }
}
