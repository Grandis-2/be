package com.grandis.nova.member.auth.infrastructure.redis;

import com.grandis.nova.common.security.AuthRedisKeys;
import com.grandis.nova.member.auth.application.RevocationStore;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 폐기 표식 저장소. revokeAll 은 세션을 열거하지 않고 회원 not-before 키 하나로 한다.
 * 값 형식은 RevocationRedisChecker 가 읽는 것과 맞춘다: revoked-sid 는 "1", nbf 는 epoch 초 십진 문자열.
 */
@Repository
public class RevocationRedisStore implements RevocationStore {

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RevocationRedisStore(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public void revokeSession(UUID sessionId, Duration accessTokenTtl) {
        redis.opsForValue().set(AuthRedisKeys.revokedSession(sessionId), "1", accessTokenTtl);
    }

    @Override
    public void revokeAll(String subject, Duration refreshTokenTtl) {
        // iat 가 초 정밀도라 값도 초다. 같은 초 발급은 체커가 iat ≤ nbf 로 거부한다.
        redis.opsForValue().set(AuthRedisKeys.notBefore(subject), String.valueOf(clock.instant().getEpochSecond()), refreshTokenTtl);
    }
}
