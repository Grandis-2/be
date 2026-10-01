package com.grandis.nova.waitingroom.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    static final String JWKS = "https://member.example.com/.well-known/jwks.json";

    @Test
    void 기본값은_대상_nova_api_와_시계_오차_30초다() {
        JwtProperties properties = new JwtProperties("nova", null, JWKS, null, null, null);

        assertThat(properties.audience()).isEqualTo("nova-api");
        assertThat(properties.clockSkew()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.publicKeys()).isEmpty();
    }

    @Test
    void 발급자나_키_출처가_없으면_기동을_막는다() {
        assertThatThrownBy(() -> new JwtProperties(" ", null, JWKS, null, null, null))
                .hasMessageContaining("jwt.issuer");
        assertThatThrownBy(() -> new JwtProperties("nova", null, null, null, Map.of(), null))
                .hasMessageContaining("no key source");
    }

    @Test
    void JWKS_는_https_만_받고_http_는_명시해야_받는다() {
        String http = "http://member.ureca.local:8080/.well-known/jwks.json";

        assertThatThrownBy(() -> new JwtProperties("nova", null, http, null, null, null))
                .hasMessageContaining("https");
        assertThat(new JwtProperties("nova", null, http, true, null, null).jwkSetUri()).isEqualTo(http);
    }

    @Test
    void 문자열로_찍어도_공개키_본문은_나오지_않는다() {
        JwtProperties properties = new JwtProperties("nova", null, null, null, Map.of("k1", "PEM-BODY"), null);

        assertThat(properties.toString()).contains("k1").doesNotContain("PEM-BODY");
    }
}
