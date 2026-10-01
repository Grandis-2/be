package com.grandis.nova.waitingroom.auth;

import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.waitingroom.web.ErrorResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.time.Clock;

/**
 * 모든 API 는 member 의 액세스 토큰으로 인증한다. 세션 · 쿠키 없이 요청마다 검증한다.
 * 회원은 토큰의 sub 로만 식별하고, 권한은 role 클레임(USER · ADMIN)이다. 폐기 여부(로그아웃 · 강제 만료)는
 * 요청마다 저장소를 치지 않으려고 여기서 보지 않는다 — 접수의 최종 판정은 preorder 가 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** member JWKS 를 기동 때와 주기마다 받는다. jwk-set-uri 가 없으면(정적 공개키만) 만들지 않는다. */
    @Bean
    @Conditional(JwkSetUriConfigured.class)
    JwkSetCache jwkSetCache(JwtProperties properties, WebClient.Builder webClients, Clock clock) {
        if (properties.jwkSetUri().startsWith("http://")) {
            log.warn("jwt.jwk-set-uri 가 http 다 — 경로를 가로채면 공개키를 바꿔 끼울 수 있다. VPC 안에서만 쓴다");
        }
        return new JwkSetCache(webClients.build().get().uri(properties.jwkSetUri()).retrieve().bodyToMono(String.class),
                clock);
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(JwtProperties properties, ObjectProvider<JwkSetCache> jwkSetCache, Clock clock) {
        return JwtDecoderFactory.create(properties, jwkSetCache.getIfAvailable(), clock);
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder,
                                                  ErrorResponses errorResponses,
                                                  @Value("${server.port:8080}") int serverPort,
                                                  @Value("${management.server.port:-1}") int managementPort) {
        ServerAuthenticationEntryPoint unauthenticated = (exchange, ex) -> {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
                    ex instanceof OAuth2AuthenticationException ? "Bearer error=\"invalid_token\"" : "Bearer");
            return errorResponses.write(exchange, CommonErrorCode.UNAUTHENTICATED);
        };
        ServerAccessDeniedHandler forbidden = (exchange, ex) -> errorResponses.write(exchange, CommonErrorCode.FORBIDDEN);
        ServerAuthenticationFailureHandler failed = (webFilterExchange, ex) -> {
            ServerWebExchange exchange = webFilterExchange.getExchange();
            // 키를 못 받은 것은 토큰 탓이 아니다. 상태 전이 로그는 JWKS 캐시가 남기므로 요청마다 남기지 않는다
            if (hasCause(ex, SigningKeysUnavailableException.class)) {
                log.debug("액세스 토큰을 검증할 키가 없다: {}", ex.getMessage());
                return errorResponses.write(exchange, CommonErrorCode.DEPENDENCY_UNAVAILABLE);
            }
            if (ex instanceof AuthenticationServiceException) {
                log.error("액세스 토큰 검증 중 예상하지 못한 오류", ex);
                return errorResponses.write(exchange, CommonErrorCode.INTERNAL_ERROR);
            }
            return unauthenticated.commence(exchange, ex);
        };
        return http
                // 프론트와 API 가 한 도메인이라 CORS 를 켜지 않는다(다른 서비스와 같다)
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        .matchers(managementPort(serverPort, managementPort)).permitAll()
                        .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtDecoder(jwtDecoder).jwtAuthenticationConverter(authenticationConverter()))
                        .authenticationEntryPoint(unauthenticated)
                        .authenticationFailureHandler(failed)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .build();
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    /** 관리 포트로 온 요청만. 서비스 포트(ALB)로는 actuator 를 내놓지 않는다 — 두 포트가 같으면 아무것도 열지 않는다. */
    private static ServerWebExchangeMatcher managementPort(int serverPort, int managementPort) {
        return exchange -> {
            InetSocketAddress local = exchange.getRequest().getLocalAddress();
            boolean onManagementPort = managementPort > 0 && managementPort != serverPort
                    && local != null && local.getPort() == managementPort;
            return onManagementPort ? ServerWebExchangeMatcher.MatchResult.match()
                    : ServerWebExchangeMatcher.MatchResult.notMatch();
        };
    }

    /** role 클레임을 ROLE_USER · ROLE_ADMIN 권한으로, sub 를 인증 주체 이름으로. */
    private static ReactiveJwtAuthenticationConverterAdapter authenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtDecoderFactory.CLAIM_ROLE);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
