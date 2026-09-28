package com.grandis.nova.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BearerTokens — Authorization: Bearer 에서 토큰을 꺼내는 규칙")
class BearerTokensTest {

    static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMDEifQ.c2ln-_bmF0dXJl";

    @Test
    @DisplayName("잘 갖춘 모양이면 토큰 — 스킴은 대소문자 무시, 공백은 하나 이상, 앞뒤 공백은 무시")
    void wellFormed() {
        assertThat(BearerTokens.parse("Bearer " + JWT)).contains(JWT);
        assertThat(BearerTokens.parse("bearer " + JWT)).contains(JWT);
        assertThat(BearerTokens.parse("BEARER   " + JWT)).contains(JWT);
        assertThat(BearerTokens.parse("  Bearer " + JWT + "  ")).contains(JWT);
        assertThat(BearerTokens.parse("Bearer abc==")).contains("abc==");
        assertThat(BearerTokens.value(JWT)).isEqualTo("Bearer " + JWT);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"", "   ", "Bearer", "Bearer ", "Basic dXNlcjpwdw==", "Token " + JWT, "Bearer" + JWT,
            "Bearer " + JWT + " extra", "Bearer " + JWT + ",", "Bearer 토큰", "Bearer a\tb", "Bearer =abc"})
    @DisplayName("빈 값 · 스킴만 · 다른 스킴 · 공백 없음 · 뒤에 더 붙음 · 허용 밖 문자는 전부 자격 증명 없음")
    void malformedIsAbsent(String header) {
        assertThat(BearerTokens.parse(header)).isEmpty();
    }

    @Test
    @DisplayName("헤더가 없거나 둘 이상이면 자격 증명 없음 — 어느 것인지 모호한 요청을 추측하지 않는다")
    void requestLevel() {
        assertThat(BearerTokens.extract(new MockHttpServletRequest())).isEmpty();
        assertThat(BearerTokens.parse(null)).isEmpty();

        MockHttpServletRequest one = new MockHttpServletRequest();
        one.addHeader(BearerTokens.HEADER, BearerTokens.value(JWT));
        assertThat(BearerTokens.extract(one)).contains(JWT);

        MockHttpServletRequest two = new MockHttpServletRequest();
        two.addHeader(BearerTokens.HEADER, BearerTokens.value(JWT));
        two.addHeader(BearerTokens.HEADER, BearerTokens.value("other"));
        assertThat(BearerTokens.extract(two)).isEmpty();
    }
}
