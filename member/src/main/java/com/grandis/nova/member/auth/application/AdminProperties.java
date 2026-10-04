package com.grandis.nova.member.auth.application;

import jakarta.validation.constraints.NotBlank;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * `admin.username`, `admin.password-hash`. 둘 다 Secrets Manager 참조(ECS secrets 주입). 평문 비밀번호는 어디에도 없다.
 * 해시는 `htpasswd -nBC 12 "" | tr -d ':\n'` 로 만든 bcrypt 다. 형식이 bcrypt 가 아니거나 cost 가 12 미만이면 기동에서 걸린다(실측: AdminPropertiesTest) —
 * 평문을 넣고 "로그인만 안 되는" 상태와, 약한 cost 로 만든 해시가 조용히 쓰이는 상태를 막는다. cost 는 12~14 만 받는다 — cost 1 마다 비교 시간이
 * 두 배다(실측 한 번 비교: 12 = 253ms · 13 = 510ms · 14 = 969ms · 15 = 1.9s). 상한이 없으면 오타 난 해시(cost 31 ≈ 36시간) 하나로 서버는 뜨고
 * 관리자 로그인 한 번이 CPU 하나를 하루 넘게 잡는다. 14 를 넘기려면 부하를 재고 이 상한을 같이 올린다.
 *
 * <p><b>password-hash 에는 바인딩 검증(@NotBlank · @Pattern)을 붙이지 않는다.</b> 붙이면 Boot 의 BindValidationFailureAnalyzer 가 기동 실패
 * 보고서에 거부된 값을 그대로 찍는다(실측: 평문 {@code MyPlainSecret123!} 이 "Value: ..." 줄에 나왔다) — 운영자가 실수로 평문을 넣으면 그
 * 비밀번호가 중앙 로그에 남는다. 검사는 생성자에서 하고 메시지에 값을 넣지 않는다(JwtProperties 의 개인키와 같은 규칙).
 *
 * 자격증명을 바꾼 뒤에는 운영자가 배포 완료 뒤 {@code DELETE /api/v1/admin/sessions} 를 한 번 불러 이전 관리자 세션을 전부 끊는다(D-16).
 * 서버가 자동으로 감지하지 않는다 — 롤링 배포 · 키 유실 · 세대 누락에서 경쟁이 생겨 거짓 안심을 줬다(리뷰 실측).
 */
@Validated
@ConfigurationProperties("admin")
public record AdminProperties(
        @NotBlank String username,
        String passwordHash
) {

    /** bcrypt 모양: 버전 · cost 12..14 · 본문 53자(bcrypt 의 base64 문자 `./A-Za-z0-9`). 본문이 그 밖이면 기동은 되고 로그인만 실패하던 설정을 여기서 막는다. */
    private static final Pattern BCRYPT_COST_12_TO_14 = Pattern.compile("^\\$2[aby]\\$1[2-4]\\$[./A-Za-z0-9]{53}$");

    public AdminProperties {
        if (passwordHash == null || !BCRYPT_COST_12_TO_14.matcher(passwordHash).matches()) {
            // 값은 메시지에 넣지 않는다 — 이 메시지는 기동 실패 로그에 그대로 나간다
            throw new IllegalArgumentException("admin.password-hash must be a bcrypt hash with cost 12..14");
        }
    }

    @Override
    public String toString() {
        return "AdminProperties[username=" + username + ", passwordHash=****]";
    }
}
