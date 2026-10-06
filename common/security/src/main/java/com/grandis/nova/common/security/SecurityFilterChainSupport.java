package com.grandis.nova.common.security;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;   // Boot 4 = Jackson 3 (tools.jackson)

/**
 * HTTP 서비스 4개의 SecurityConfig 가 공통으로 부르는 것. 각 서비스는 인가 규칙만 넘긴다.
 *
 * 인가 규칙은 인자로 **강제**한다. 규칙 블록을 빠뜨린 서비스는 스프링 시큐리티가 인가 필터를 안 걸어 전 경로가 열린 채 뜨는데,
 * 그건 아무 데서도 안 보이는 fail-open 이다. 산문("이 블록이 없으면 안 된다")은 안 읽히지만 컴파일러는 읽힌다.
 * anyRequest() 는 규칙의 마지막이어야 하므로 여기서 기본을 먼저 깔 수 없다 — 그래서 인자다.
 *
 * - 세션 없음(STATELESS). 인증은 JWT 로만.
 * - CSRF 끔. 액세스가 쿠키가 아니라 헤더라 브라우저가 자동으로 붙이지 않는다.
 * - CORS 끔. 프론트와 API 가 한 도메인이다. 도메인이 갈리면 그때 켠다.
 * - 401/403 은 JsonAuthFailureHandlers 가 api-spec 봉투로.
 * - JwtAuthenticationFilter 를 UsernamePasswordAuthenticationFilter 앞에. 필터 인스턴스는 여기서만 만든다(빈 아님).
 * - 메서드 보안(@EnableMethodSecurity)은 쓰지 않는다. 권한 규칙은 URL 로만 — api-spec 이 경로별로 권한을 적었고,
 *   두 군데에 규칙이 있으면 한쪽이 잊힌다.
 */
@Component
public class SecurityFilterChainSupport {

    private final JwtTokenProvider provider;
    private final RevocationChecker revocationChecker;
    private final RevocationFailurePolicy policy;
    private final JsonAuthFailureHandlers handlers;

    public SecurityFilterChainSupport(JwtTokenProvider provider, RevocationChecker revocationChecker,
                                      RevocationFailurePolicy policy, ObjectMapper objectMapper) {
        this.provider = provider;
        this.revocationChecker = revocationChecker;
        this.policy = policy;
        this.handlers = new JsonAuthFailureHandlers(objectMapper);
    }

    public SecurityFilterChain build(
            HttpSecurity http,
            Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry> authorization)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                .addFilterBefore(new JwtAuthenticationFilter(provider, revocationChecker, policy),
                        UsernamePasswordAuthenticationFilter.class)
                .anonymous(Customizer.withDefaults())
                .authorizeHttpRequests(authorization)
                .build();
    }
}
