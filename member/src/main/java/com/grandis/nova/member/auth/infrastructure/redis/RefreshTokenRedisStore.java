package com.grandis.nova.member.auth.infrastructure.redis;

import com.grandis.nova.common.security.AuthRedisKeys;
import com.grandis.nova.member.auth.application.AdminRefreshTokenStore;
import com.grandis.nova.member.auth.application.RefreshTokens;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/**
 * 관리자 세션의 리프레시 jti 를 두는 Lua 비교교환 저장소. 회원 리프레시는 여기가 아니라 `shop.refresh_tokens` 가 정본이다.
 * 회전은 GET → 비교 → SET 을 서버 안에서 한 번에 한다. 클라이언트에서 GET 하고 SET 하면 그 사이에 다른 요청이 끼어 둘 다 성공한다.
 *
 * <p>값은 발급 직후 `지금jti` 하나, 회전 뒤에는 `지금jti|바로앞jti|유예끝(Redis 서버 시각, epoch ms)` 이다. 회전 유예를 키 하나에 담는다 — 키를 둘로
 * 나누면 클러스터에서 슬롯이 갈려 한 스크립트로 묶을 수 없다. 바로 앞 jti 가 맞으면 지금 jti 는 그것이 만든 다음 토큰이고 아직 안 쓰였다(쓰였으면
 * 바로 앞 자리가 그것으로 바뀌었다). 유예 안의 다시 회전은 유예 끝을 그대로 두어 창이 늘지 않는다. 시각은 앱이 아니라 Redis 의 TIME 으로 잰다 —
 * 인스턴스마다 시계가 달라도 한 곳에서 판정한다. 대가: 배포 중 옛 버전 인스턴스는 이 모양을 읽지 못해(지금 jti 와 다르다고 본다) 회전된 세션을
 * 재사용으로 끊는다.
 */
@Repository
public class RefreshTokenRedisStore implements AdminRefreshTokenStore {

    /**
     * KEYS[1]=세션 키, ARGV[1]=기대 jti, ARGV[2]=새 jti, ARGV[3]=TTL(ms), ARGV[4]=유예(ms).
     * 1 이면 교체됨, 2 면 유예 안의 다시 회전, 0 이면 키 없음 또는 그 밖의 재사용.
     */
    static final DefaultRedisScript<Long> ROTATE = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if value == false then
                return 0
            end
            local current, previous, graceUntil = value, nil, 0
            local first = string.find(value, '|', 1, true)
            if first then
                local second = string.find(value, '|', first + 1, true)
                current = string.sub(value, 1, first - 1)
                previous = string.sub(value, first + 1, second - 1)
                graceUntil = tonumber(string.sub(value, second + 1))
            end
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            if current == ARGV[1] then
                redis.call('SET', KEYS[1], ARGV[2] .. '|' .. ARGV[1] .. '|' .. string.format('%.0f', now + tonumber(ARGV[4])), 'PX', ARGV[3])
                return 1
            end
            if previous == ARGV[1] and now < graceUntil then
                redis.call('SET', KEYS[1], ARGV[2] .. '|' .. previous .. '|' .. string.format('%.0f', graceUntil), 'PX', ARGV[3])
                return 2
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RefreshTokenRedisStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(UUID sessionId, UUID refreshTokenId, Duration ttl) {
        redis.opsForValue().set(AuthRedisKeys.refresh(sessionId), refreshTokenId.toString(), ttl);
    }

    @Override
    public boolean rotate(UUID sessionId, UUID expectedRefreshTokenId, UUID newRefreshTokenId, Duration ttl) {
        // toMillis 는 내림이라 1ms 미만이면 PX 0 이 되고 Redis 가 "invalid expire time" 으로 거절해 500 이 된다. 하한 1ms.
        long ttlMillis = Math.max(1, ttl.toMillis());
        Long result = redis.execute(ROTATE, List.of(AuthRedisKeys.refresh(sessionId)),
                expectedRefreshTokenId.toString(), newRefreshTokenId.toString(), String.valueOf(ttlMillis),
                String.valueOf(RefreshTokens.ROTATION_GRACE.toMillis()));
        return result != null && result > 0;
    }

    @Override
    public void delete(UUID sessionId) {
        redis.delete(AuthRedisKeys.refresh(sessionId));
    }
}
