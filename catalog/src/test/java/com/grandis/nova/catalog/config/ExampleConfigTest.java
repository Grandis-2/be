package com.grandis.nova.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.common.security.JwtProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * application.yml.example 이 그대로 읽히고 인증 설정이 바인딩되는가. 실측(리뷰): 최상위 `jwt:` 키가 두 번 있으면 로더가
 * DuplicateKeyException 을 내 예시를 복사한 운영 설정이 기동을 못 한다. 유효기간이 빠져도 @NotNull 에서 거부된다. 둘 다 여기서 잡는다.
 */
@DisplayName("application.yml.example — 읽히고, 인증 설정이 바인딩된다")
class ExampleConfigTest {

    @Test
    @DisplayName("YAML 이 읽히고(중복 키 없음) jwt 가 검증 전용으로 바인딩된다 — 발급자 · 유효기간 · JWKS 주소")
    void exampleLoadsAndJwtBinds() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("example", new ClassPathResource("application.yml.example"));
        assertThat(sources).isNotEmpty();
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addLast);

        JwtProperties jwt = Binder.get(environment).bind("jwt", JwtProperties.class).get();

        assertThat(jwt.issuer()).isEqualTo("nova");
        assertThat(jwt.audience()).isEqualTo("nova-api");
        assertThat(jwt.jwkSetUri()).startsWith("https://");
        assertThat(jwt.accessTokenValidity()).isEqualTo(Duration.ofMinutes(30));
        assertThat(jwt.refreshTokenValidity()).isEqualTo(Duration.ofDays(14));
        assertThat(jwt.issues()).as("catalog 는 발급하지 않는다(개인키 없음)").isFalse();
        assertThat(ConfigurationPropertySources.get(environment)).isNotNull();
    }
}
