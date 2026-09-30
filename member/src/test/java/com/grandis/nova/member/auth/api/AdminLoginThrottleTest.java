package com.grandis.nova.member.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.common.security.AuthRedisKeys;
import com.grandis.nova.common.web.RequestIdFilter;
import com.grandis.nova.member.support.MemberIntegrationTest;
import com.grandis.nova.member.support.MemberTestContext;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 관리자 로그인 시도 제한(IP 별, 기본 1분 5회). 실제 Redis. 요청은 운영처럼 X-Forwarded-For 를 달고 온다 —
 * "클라이언트가 넣은 값, CloudFront 가 붙인 실제 IP, ALB 가 붙인 엣지 IP". 믿는 프록시 2(기본)라 오른쪽에서 두 번째가 실제 IP 다.
 */
@MemberIntegrationTest
@DisplayName("관리자 로그인 시도 제한 — IP 별 5회, 넘으면 bcrypt 없이 429")
class AdminLoginThrottleTest {

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired RequestIdFilter requestIdFilter;
    @MockitoSpyBean PasswordEncoder encoder;
    @MockitoSpyBean StringRedisTemplate redis;

    MockMvc mockMvc;
    String viewer;   // 이 시험의 실제 클라이언트 IP — 시험끼리 수가 섞이지 않게 매번 새로

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter, springSecurityFilterChain).build();
        viewer = randomIp();
    }

    @Test
    @DisplayName("다섯 번까지는 401, 여섯 번째부터 429 + Retry-After — 그때는 맞는 비밀번호도 429 다")
    void blocksAfterFiveAttempts() throws Exception {
        for (int i = 0; i < 5; i++) {
            login("wrong", viewer, "198.51.100.7").andExpect(status().isUnauthorized());
        }
        login("wrong", viewer, "198.51.100.7")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_LOGIN_ATTEMPTS"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").isNumber());
        login(MemberTestContext.ADMIN_PASSWORD, viewer, "198.51.100.7").andExpect(status().isTooManyRequests());
        long retryAfter = Long.parseLong(login("wrong", viewer, "x").andReturn().getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("클라이언트가 X-Forwarded-For 왼쪽을 요청마다 바꿔도 실제 IP(CloudFront 가 붙인 칸)로 센다 — 위조로 제한을 못 피한다")
    void spoofedLeftEntriesDoNotEscape() throws Exception {
        for (int i = 0; i < 5; i++) {
            login("wrong", viewer, randomIp()).andExpect(status().isUnauthorized());
        }
        login("wrong", viewer, randomIp()).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("다른 IP 는 막히지 않는다")
    void otherClientsAreNotAffected() throws Exception {
        for (int i = 0; i < 6; i++) {
            login("wrong", viewer, "x");
        }
        login("wrong", viewer, "x").andExpect(status().isTooManyRequests());   // 대조 — 이 IP 는 막혔다
        login(MemberTestContext.ADMIN_PASSWORD, randomIp(), "x").andExpect(status().isOk());
    }

    @Test
    @DisplayName("로그인에 성공하면 그 IP 의 수를 지운다. 수는 만료(창 1분)를 달고 생긴다")
    void successResetsAndCounterExpires() throws Exception {
        login("wrong", viewer, "x").andExpect(status().isUnauthorized());
        Long ttl = redis.getExpire(AuthRedisKeys.adminLoginAttempts(viewer));
        assertThat(ttl).as("만료 없는 키가 남으면 그 IP 는 영원히 막힌다").isBetween(1L, 60L);

        login(MemberTestContext.ADMIN_PASSWORD, viewer, "x").andExpect(status().isOk());
        assertThat(redis.hasKey(AuthRedisKeys.adminLoginAttempts(viewer))).isFalse();
    }

    @Test
    @DisplayName("막힌 뒤의 시도는 bcrypt 비교를 하지 않는다 — 요청마다 도는 비싼 비교를 한도만큼만 쓴다")
    void blockedAttemptsSkipBcrypt() throws Exception {
        for (int i = 0; i < 5; i++) {
            login("wrong", viewer, "x");
        }
        clearInvocations(encoder);
        login("wrong", viewer, "x").andExpect(status().isTooManyRequests());
        verify(encoder, never()).matches(any(), any());
    }

    @Test
    @DisplayName("시도를 셀 수 없으면(Redis 장애) 503 retryable 로 거절하고 bcrypt 비교도 하지 않는다 — 제한 없이 받는 우회를 남기지 않는다")
    void redisDownRejectsWith503() throws Exception {
        doThrow(new RedisConnectionFailureException("down")).when(redis).execute(any(RedisScript.class), anyList(), anyString());
        clearInvocations(encoder);
        login(MemberTestContext.ADMIN_PASSWORD, viewer, "x")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.details.retryable").value(true));
        verify(encoder, never()).matches(any(), any());
    }

    @Test
    @DisplayName("만료 없는 키(과거 버그 · 수동 조작)를 만나면 만료를 다시 건다 — 안 걸면 그 IP 는 영원히 막힌다")
    void ttlLessKeyIsRepaired() throws Exception {
        String key = AuthRedisKeys.adminLoginAttempts(viewer);
        redis.opsForValue().set(key, "2");
        assertThat(redis.getExpire(key)).isEqualTo(-1L);

        login("wrong", viewer, "x").andExpect(status().isUnauthorized());

        assertThat(redis.getExpire(key)).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("Retry-After 는 창 전체가 아니라 남은 시간을 초로 올린 값이다(30.5초 남음 → 31)")
    void retryAfterIsRemainingTimeRoundedUp() throws Exception {
        String key = AuthRedisKeys.adminLoginAttempts(viewer);
        redis.opsForValue().set(key, "5", java.time.Duration.ofMillis(30_500));

        String retryAfter = login("wrong", viewer, "x").andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getHeader("Retry-After");

        assertThat(Long.parseLong(retryAfter)).isEqualTo(31L);
    }

    @Test
    @DisplayName("성공 뒤 수 지우기가 실패해도 로그인은 성공한다 — 수는 창이 끝나면 사라진다")
    void resetFailureDoesNotFailLogin() throws Exception {
        doThrow(new RedisConnectionFailureException("down")).when(redis).delete(anyString());
        login(MemberTestContext.ADMIN_PASSWORD, viewer, "x").andExpect(status().isOk());
    }

    private ResultActions login(String password, String clientIp, String spoofed) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/session").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", spoofed + ", " + clientIp + ", 130.176.0.1")   // 클라이언트 값, CloudFront 가 붙인 실제 IP, ALB 가 붙인 엣지 IP
                .content("{\"username\":\"admin\",\"password\":\"" + password + "\"}"));
    }

    /** 시험마다 다른 IP. 문서용 대역(203.0.113.0/24)과 겹치는 모양이면 충분하다 — 키에만 쓰인다. */
    private static String randomIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "203." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
    }
}
