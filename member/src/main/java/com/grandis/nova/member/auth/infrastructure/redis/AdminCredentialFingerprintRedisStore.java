package com.grandis.nova.member.auth.infrastructure.redis;

import com.grandis.nova.common.security.AuthRedisKeys;
import com.grandis.nova.member.auth.application.AdminCredentialFingerprintStore;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/** 키 하나, TTL 없음 — 다음 교체 때까지 남아 있어야 비교할 수 있다. 키 이름은 {@link AuthRedisKeys}(인증 키의 단일 출처). */
@Repository
public class AdminCredentialFingerprintRedisStore implements AdminCredentialFingerprintStore {

    public static final String KEY = AuthRedisKeys.ADMIN_CREDENTIAL_FINGERPRINT;

    private final StringRedisTemplate redis;

    public AdminCredentialFingerprintRedisStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> find() {
        return Optional.ofNullable(redis.opsForValue().get(KEY));
    }

    @Override
    public void save(String fingerprint) {
        redis.opsForValue().set(KEY, fingerprint);
    }
}
