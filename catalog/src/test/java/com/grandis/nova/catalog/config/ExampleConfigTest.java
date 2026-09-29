package com.grandis.nova.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandis.nova.common.security.JwtProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.validation.MessageInterpolatorFactory;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

/**
 * application.yml.example 이 그대로 읽히고 인증 설정이 바인딩되는가. 실측(리뷰): 최상위 `jwt:` 키가 두 번 있으면 로더가
 * DuplicateKeyException 을 내 예시를 복사한 운영 설정이 기동을 못 한다. 유효기간이 빠져도 @NotNull 에서 거부된다.
 * 예시가 통과하는 것만 보면 "이 시험이 무엇을 막는지" 가 안 보이므로 두 실패 사례를 같은 경로로 직접 태운다(CodeRabbit 지적).
 */
@DisplayName("application.yml.example — 읽히고, 인증 설정이 바인딩된다")
class ExampleConfigTest {

    private static final String VALID_JWT = """
            jwt:
              issuer: nova
              audience: nova-api
              jwk-set-uri: https://member.example.com/.well-known/jwks.json
              access-token-validity: 30m
              refresh-token-validity: 14d
            """;

    @Test
    @DisplayName("예시 YAML 이 읽히고(중복 키 없음) jwt 가 검증 전용으로 바인딩된다 — 발급자 · 유효기간 · JWKS 주소")
    void exampleLoadsAndJwtBinds() throws IOException {
        JwtProperties jwt = bindJwt(new ClassPathResource("application.yml.example"));

        assertThat(jwt.issuer()).isEqualTo("nova");
        assertThat(jwt.audience()).isEqualTo("nova-api");
        assertThat(jwt.jwkSetUri()).startsWith("https://");
        assertThat(jwt.accessTokenValidity()).isEqualTo(Duration.ofMinutes(30));
        assertThat(jwt.refreshTokenValidity()).isEqualTo(Duration.ofDays(14));
        assertThat(jwt.issues()).as("catalog 는 발급하지 않는다(개인키 없음)").isFalse();
    }

    @Test
    @DisplayName("대조군 — 같은 경로가 잘 갖춘 jwt 블록은 통과시킨다")
    void controlValidBlockBinds() throws IOException {
        assertThat(bindJwt(yaml(VALID_JWT)).accessTokenValidity()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("최상위 jwt 키가 두 번이면 로더가 거부한다 — 예시를 복사한 설정이 기동을 못 하는 그 실패")
    void duplicateTopLevelKeyIsRejectedByTheLoader() {
        assertThatThrownBy(() -> bindJwt(yaml(VALID_JWT + "jwt:\n  issuer: nova\n")))
                .hasMessageContainingAll("duplicate key", "jwt");
    }

    @Test
    @DisplayName("유효기간이 빠지면 바인딩 검증이 거부한다(@NotNull) — 발급하지 않는 서비스에도 필수 값이다")
    void missingValidityIsRejectedByValidation() {
        assertThatThrownBy(() -> bindJwt(yaml(VALID_JWT.replace("  access-token-validity: 30m\n", ""))))
                .isInstanceOf(BindException.class)   // 기동 때 보이는 것과 같은 예외 — 원인 사슬 끝에 어느 칸인지가 적힌다
                .satisfies(e -> {
                    Throwable root = e;
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(root.getMessage()).containsIgnoringCase("validity");
                });
    }

    /** 운영 기동과 같은 경로 — YAML 로더 → Binder + jakarta 검증(@NotNull · @NotBlank). */
    private static JwtProperties bindJwt(Resource resource) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("yaml", resource);
        assertThat(sources).isNotEmpty();
        Validator validator = Validation.byDefaultProvider().configure()
                .messageInterpolator(new MessageInterpolatorFactory().getObject()).buildValidatorFactory().getValidator();
        // YAML 만 본다 — StandardEnvironment 를 쓰면 시스템 속성 · 환경 변수(JWT_REFRESH_TOKEN_VALIDITY 같은)가 빠진 칸을 채워 준다(CodeRabbit 지적)
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("jwt", Bindable.of(JwtProperties.class), new ValidationBindHandler(new SpringValidatorAdapter(validator)))
                .get();
    }

    private static Resource yaml(String text) {
        return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "inline.yml";
            }
        };
    }
}
