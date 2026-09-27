package com.grandis.nova.member.auth.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * `admin.username`, `admin.password-hash`. 둘 다 Secrets Manager 참조(ECS secrets 주입). 평문 비밀번호는 어디에도 없다.
 * 해시는 `htpasswd -nBC 12 "" | tr -d ':\n'` 로 만든 bcrypt 다. 형식이 bcrypt 가 아니거나 cost 가 12 미만이면 기동에서 걸린다(실측: AdminPropertiesTest) —
 * 평문을 넣고 "로그인만 안 되는" 상태와, 약한 cost 로 만든 해시가 조용히 쓰이는 상태를 막는다. cost 는 올릴 수 있다(12 이상, bcrypt 상한 31).
 */
@Validated
@ConfigurationProperties("admin")
public record AdminProperties(
        @NotBlank String username,
        @NotBlank @Pattern(regexp = "^\\$2[aby]\\$(1[2-9]|2\\d|3[01])\\$.{53}$", message = "admin.password-hash must be a bcrypt hash with cost 12..31") String passwordHash
) {

    @Override
    public String toString() {
        return "AdminProperties[username=" + username + ", passwordHash=****]";
    }
}
