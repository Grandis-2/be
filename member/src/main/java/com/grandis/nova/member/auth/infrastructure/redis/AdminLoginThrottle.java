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
 * <p><b>두 가지를 센다.</b> IP 별(기본 5회)과 모든 IP 합계(기본 60회). 합계는 IP 별 제한을 <b>통과한</b> 시도만 센다 — 막힌 IP 의 요청까지
 * 세면 IP 하나가 두드리기만 해도 모든 관리자 로그인이 막힌다. 합계는 IP 를 바꿔 가며 오는 공격에도 bcrypt 부하(한 번 약 0.25초)에 천장을 둔다.
 * 로그인에 성공하면 둘 다 지운다(사용자 결정 2026-09-30) — 공격 중 정상 로그인이 있으면 합계가 다시 열리지만, 그건 정상 로그인이 있을 때뿐이다.
 * <b>대가:</b> IP 12 개(12 × 5 = 60)면 매분 합계를 채울 수 있고, 그동안은 정상 관리자도 맞는 비밀번호로 429 다(성공 초기화가 올 기회가 없다).
 * 합계가 차면 경고 로그를 남긴다 — 운영자는 WAF 로 공격 IP 를 막는다.
 * 두 키는 따로 센다(각각 원자적). 한 스크립트로 묶으면 Redis 클러스터에서 키 슬롯이 달라 실패한다.
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
            Optional<Duration> perIp = count(AuthRedisKeys.adminLoginAttempts(clientIp), limit.maxAttempts());
            if (perIp.isPresent()) {
                return perIp;   // 막힌 IP 의 요청은 합계에 세지 않는다
            }
            Optional<Duration> total = count(AuthRedisKeys.ADMIN_LOGIN_ATTEMPTS_TOTAL, limit.maxTotalAttempts());
            // 합계가 찼다 — 분산 공격일 가능성이 높고, 그동안 정상 관리자도 막힌다. 운영자가 알아챌 신호다(D-4)
            total.ifPresent(retryAfter -> log.warn("admin login total cap reached (all IPs), rejecting for {}s", retryAfter.toSeconds()));
            return total;
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

    /** 한 키를 센다(이번 시도 포함). 한도를 넘었으면 남은 시간. */
    private Optional<Duration> count(String key, int max) {
        List<?> result = redis.execute(COUNT, List.of(key), String.valueOf(limit.window().toMillis()));
        long attempts = ((Number) result.get(0)).longValue();
        long ttlMillis = ((Number) result.get(1)).longValue();
        if (attempts > max) {
            return Optional.of(Duration.ofMillis(Math.max(ttlMillis, 1)));
        }
        return Optional.empty();
    }

    /** 로그인 성공 — 그 IP 의 수와 합계를 지운다. 실패해도 창이 끝나면 사라지므로 오류는 삼킨다. */
    public void reset(String clientIp) {
        try {
            redis.delete(AuthRedisKeys.adminLoginAttempts(clientIp));
            redis.delete(AuthRedisKeys.ADMIN_LOGIN_ATTEMPTS_TOTAL);
        } catch (DataAccessException e) {
            log.warn("admin login throttle reset failed: {}", e.getClass().getSimpleName());
        }
    }
}
