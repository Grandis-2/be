package com.grandis.nova.member.support;

import com.grandis.nova.member.auth.application.AdminCredentialFingerprintStore;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 시험 대역. 컨텍스트마다 관리자 해시가 달라 Redis 를 같이 쓰면 컨텍스트가 바뀔 때마다 "자격증명 교체" 로 폐기 표식이 심겨
 * 같은 초에 발급된 다른 시험의 관리자 토큰이 거부됐다(실측). 컨텍스트 안에서만 사는 저장소로 그 간섭을 끊는다.
 * 실제 Redis 저장소로 기동을 태우는 시험은 {@code auth.test.fingerprint-store=redis} 로 이 대역을 뺀다(AdminCredentialRotationBootTest).
 */
public class InMemoryAdminCredentialFingerprintStore implements AdminCredentialFingerprintStore {

    private final AtomicReference<String> value = new AtomicReference<>();

    @Override
    public Optional<String> find() {
        return Optional.ofNullable(value.get());
    }

    @Override
    public void save(String fingerprint) {
        value.set(fingerprint);
    }

    public void clear() {
        value.set(null);
    }
}
