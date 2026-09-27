package com.grandis.nova.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.common.security.JwtAuthenticationFilter;
import com.grandis.nova.common.security.JwtProperties;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.common.web.RequestIdFilter;
import com.grandis.nova.common.security.RevocationCheckProperties;
import com.grandis.nova.member.support.Containers;
import com.grandis.nova.member.auth.api.AuthCookies;
import com.grandis.nova.member.auth.application.AdminProperties;
import com.grandis.nova.member.auth.infrastructure.kakao.KakaoProperties;
import java.net.Socket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * application.yml.example 을 그대로 읽어 컨텍스트를 띄운다(비밀값 자리표시자만 덮는다 — 자리표시자는 전부 기동을 막도록 되어 있다). 예시 파일의 키 이름이 실제 바인딩과
 * 어긋나면 여기서 잡힌다 — 예시만 고치고 코드를 안 고치는(또는 그 반대) 사고를 막는다. 아래 셋이 여기서 실측된다:
 * Hikari 격리 수준, spring.data.redis.timeout → Lettuce 명령 타임아웃, 카카오·관리자·쿠키·닫는 경로 바인딩.
 */
@SpringBootTest(classes = MemberApplication.class)
@DisplayName("application.yml.example 이 그대로 뜬다 (실제 MySQL)")
class ConfigBindingTest {

    @DynamicPropertySource
    static void fromExample(DynamicPropertyRegistry registry) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml.example"));
        Properties props = yaml.getObject();
        assertThat(props).isNotNull();
        props.forEach((k, v) -> registry.add(k.toString(), () -> v));
        // 예시의 자리표시자만 덮는다. 나머지 키·값은 예시 그대로.
        registry.add("spring.datasource.url", Containers.MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", Containers.MYSQL::getUsername);
        registry.add("spring.datasource.password", Containers.MYSQL::getPassword);
        registry.add("spring.data.redis.host", Containers::redisHost);
        registry.add("spring.data.redis.port", Containers::redisPort);
        // 시험 클래스패스에만 있는 Flyway 를 스프링이 또 돌리지 않게 한다. 스키마는 Containers 가 이미 만들었다.
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("admin.password-hash", () -> "$2a$12$" + "R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW");
        TestKeys.register(registry);   // 예시의 private-key 자리표시자를 시험 키로
    }

    @Autowired DataSource dataSource;
    @Autowired LettuceConnectionFactory redis;
    @Autowired KakaoProperties kakao;
    @Autowired AdminProperties admin;
    @Autowired AuthCookies.Settings cookie;
    @Autowired RevocationCheckProperties revocation;
    @Autowired JwtProperties jwt;
    @Autowired com.grandis.nova.member.auth.api.RefreshOriginPolicy.Settings refreshOrigins;
    @Autowired Environment env;
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired JwtTokenProvider provider;

    @Test
    @DisplayName("실측: hikari.transaction-isolation 이 커넥션의 @@transaction_isolation 을 READ-COMMITTED 로 만든다 (서버 기본은 RR)")
    void hikariIsolationIsAppliedToSessions() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT @@transaction_isolation, @@global.transaction_isolation")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("READ-COMMITTED");
                assertThat(rs.getString(2)).isEqualTo("REPEATABLE-READ");   // 서버 기본값이 RR 임을 같이 확인 — 설정이 실제로 뭔가를 바꿨다는 증거
            }
        }
    }

    @Test
    @DisplayName("실측: spring.data.redis.timeout=300ms 가 Lettuce 명령 타임아웃으로, connect-timeout=200ms 가 소켓 연결 타임아웃으로 간다")
    void redisTimeoutsBind() {
        assertThat(redis.getTimeout()).isEqualTo(300L);
        assertThat(redis.getClientConfiguration().getClientOptions())
                .hasValueSatisfying(o -> assertThat(o.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofMillis(200)));
    }

    @Test
    @DisplayName("open-in-view=false 가 예시에 있고 OSIV 인터셉터 빈이 없다 — KakaoLoginService 의 1062 재조회가 이 설정에 기댄다")
    void openInViewIsOff() {
        assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(context.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class)).isEmpty();
    }

    @Test
    @DisplayName("auth.cookie.secure=true(예시 값) 컨텍스트에서 실제 Set-Cookie 헤더에 Secure·HttpOnly·SameSite=Strict 가 실린다")
    void secureCookieFlagReachesSetCookieHeader() throws Exception {
        String access = provider.create("101", Role.USER, UUID.randomUUID(), TokenType.ACCESS);
        String setCookie = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter, springSecurityFilterChain).build()
                .perform(delete("/api/v1/session").header(JwtAuthenticationFilter.HEADER, access))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith(AuthCookies.REFRESH_TOKEN + "=")
                .contains("Secure").contains("HttpOnly").contains("SameSite=Strict").contains("Path=" + AuthCookies.PATH).contains("Max-Age=0");
    }

    @Test
    @DisplayName("실측: common:security 의 AuthenticationManager 빈 때문에 UserDetailsServiceAutoConfiguration 이 물러난다 — 인메모리 사용자 없음")
    void userDetailsServiceAutoConfigurationBacksOff() {
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AuthenticationManager.class)).containsExactly("noAuthenticationManager");
    }

    @Test
    @DisplayName("kakao.* / admin.* / auth.cookie / auth.revocation-check / jwt.* 가 예시 값 그대로 바인딩된다")
    void authPropertiesBind() {
        assertThat(kakao.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(kakao.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(kakao.allowedRedirectUris()).containsExactly("http://localhost:3000/login/kakao/callback");
        assertThat(kakao.tokenUri()).isEqualTo("https://kauth.kakao.com/oauth/token");
        assertThat(admin.username()).isEqualTo("admin");
        assertThat(cookie.secure()).isTrue();
        assertThat(revocation.failClosedPaths()).hasSize(6).contains("/api/v1/session/refresh", "/api/v1/me/**");
        assertThat(refreshOrigins.allowedOrigins()).containsExactly("http://localhost:3000");
        assertThat(jwt.issuer()).isEqualTo("nova");
        assertThat(jwt.audience()).isEqualTo("nova-api");
        assertThat(jwt.issues()).isTrue();
        assertThat(jwt.keyId()).isEqualTo(TestKeys.KID);
        assertThat(jwt.accessTokenValidity()).isEqualTo(Duration.ofMinutes(30));
        assertThat(jwt.refreshTokenValidity()).isEqualTo(Duration.ofDays(14));
    }
}
