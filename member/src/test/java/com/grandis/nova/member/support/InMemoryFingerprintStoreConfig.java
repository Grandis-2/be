package com.grandis.nova.member.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 기본은 인메모리 대역. {@code auth.test.fingerprint-store=redis} 인 컨텍스트만 실제 Redis 저장소를 쓴다. */
@TestConfiguration(proxyBeanMethods = false)
public class InMemoryFingerprintStoreConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "auth.test.fingerprint-store", havingValue = "in-memory", matchIfMissing = true)
    InMemoryAdminCredentialFingerprintStore inMemoryAdminCredentialFingerprintStore() {
        return new InMemoryAdminCredentialFingerprintStore();
    }
}
