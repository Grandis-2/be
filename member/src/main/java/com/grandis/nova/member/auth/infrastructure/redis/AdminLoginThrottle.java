package com.grandis.nova.member.auth.infrastructure.redis;

import com.grandis.nova.common.security.AuthRedisKeys;
import com.grandis.nova.member.auth.application.AdminLoginLimit;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 관리자 로그인 시도 수를 IP 별로 센다(Redis). bcrypt 비교 <b>전에</b> 센다 — 동시에 몰려와도 비싼 비교는 한도만큼만 돈다.
 * 성공하면 지운다. 실패 · 진행 중인 시도는 창이 끝날 때까지 한 번으로 남는다.
 *
 * <p>INCR 과 만료 설정을 Lua 한 번으로 한다. 따로 부르면 그 사이 프로세스가 죽을 때 만료 없는 키가 남아 그 IP 가 영원히 막힌다. 만료가 없는 키를
 * 만나면(과거 버그 · 수동 조작) 다시 건다.
 *
 * <p><b>셀 수 없으면(Redis 장애) 거절한다(fail-closed, 2026-09-30 결정).</b> 통과시켜도 얻는 게 없다 — Redis 가 죽으면 관리자 토큰 발급이
 * 리프레시를 Redis 에 쓰므로 맞는 비밀번호도 어차피 실패한다(리뷰 실측). 통과로 두면 "Redis 를 일부만 망가뜨리면 제한이 풀린다" 는 우회만 남는다.
 * 관리자 경로를 닫는 쪽에 둔 D-2 와도 맞다. 호출자는 503 retryable 로 알린다 — 장애 조치(수 초)가 끝나면 다시 시도하면 된다.
 */
@Component
public class AdminLoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(AdminLoginThrottle.class);

    /** 반환: {이번 시도를 포함한 수, 남은 만료(ms)}. */
    private static final RedisScript<List> COUNT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            local ttl = redis.call('PTTL', KEYS[1])
            if n == 1 or ttl < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
              ttl = tonumber(ARGV[1])
            end
            return {n, ttl}
            """, List.class);

    private final StringRedisTemplate redis;
    private final AdminLoginLimit limit;

    public AdminLoginThrottle(StringRedisTemplate redis, AdminLoginLimit limit) {
        this.redis = redis;
        this.limit = limit;
    }

    /**
     * 이번 시도를 센다. 한도를 넘었으면 다시 시도해도 되는 때까지의 시간, 아니면 빈 값.
     *
     * @throws Unavailable 셀 수 없다(Redis 장애) — 시도를 받지 않는다
     */
    public Optional<Duration> acquire(String clientIp) {
        try {
            List<?> result = redis.execute(COUNT, List.of(AuthRedisKeys.adminLoginAttempts(clientIp)),
                    String.valueOf(limit.window().toMillis()));
            long attempts = ((Number) result.get(0)).longValue();
            long ttlMillis = ((Number) result.get(1)).longValue();
            if (attempts > limit.maxAttempts()) {
                return Optional.of(Duration.ofMillis(Math.max(ttlMillis, 1)));
            }
            return Optional.empty();
        } catch (DataAccessException e) {
            log.warn("admin login throttle unavailable, rejecting attempt: {}", e.getClass().getSimpleName());
            throw new Unavailable(e);
        }
    }

    /** 시도 수를 셀 수 없다. 로그인을 받지 않는다(503 retryable). */
    public static final class Unavailable extends RuntimeException {

        Unavailable(DataAccessException cause) {
            super("admin login throttle unavailable", cause);
        }
    }

    /** 로그인 성공 — 그 IP 의 수를 지운다. 실패해도 창이 끝나면 사라지므로 오류는 삼킨다. */
    public void reset(String clientIp) {
        try {
            redis.delete(AuthRedisKeys.adminLoginAttempts(clientIp));
        } catch (DataAccessException e) {
            log.warn("admin login throttle reset failed: {}", e.getClass().getSimpleName());
        }
    }
}
