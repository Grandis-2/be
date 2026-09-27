package com.grandis.nova.member.auth.infrastructure.redis;

import com.grandis.nova.common.security.AuthRedisKeys;
import com.grandis.nova.member.auth.application.AdminRefreshTokenStore;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/**
 * 관리자 세션의 리프레시 jti 를 두는 Lua 비교교환 저장소. 회원 리프레시는 여기가 아니라 `shop.refresh_tokens` 가 정본이다.
 * 회전은 GET → 비교 → SET 을 서버 안에서 한 번에 한다. 클라이언트에서 GET 하고 SET 하면 그 사이에 다른 요청이 끼어 둘 다 성공한다.
 */
@Repository
public class RefreshTokenRedisStore implements AdminRefreshTokenStore {

    /** KEYS[1]=세션 키, ARGV[1]=기대 jti, ARGV[2]=새 jti, ARGV[3]=TTL(ms). 1 이면 교체됨, 0 이면 키 없음 또는 jti 불일치. */
    static final DefaultRedisScript<Long> ROTATE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current == false then
                return 0
            end
            if current ~= ARGV[1] then
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
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
                expectedRefreshTokenId.toString(), newRefreshTokenId.toString(), String.valueOf(ttlMillis));
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public void delete(UUID sessionId) {
        redis.delete(AuthRedisKeys.refresh(sessionId));
    }
}
