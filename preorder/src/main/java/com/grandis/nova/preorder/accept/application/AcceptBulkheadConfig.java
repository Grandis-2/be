package com.grandis.nova.preorder.accept.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AcceptBulkheadProperties.class)
class AcceptBulkheadConfig {

    /** 풀 크기 기본 10 은 Hikari 기본값과 같다 — 설정이 크기를 주지 않으면 풀도 10 이다. */
    @Bean
    AcceptBulkhead acceptBulkhead(AcceptBulkheadProperties properties,
                                  @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize,
                                  MeterRegistry meterRegistry, AcceptProductTags productTags) {
        return new AcceptBulkhead(properties, poolSize, meterRegistry, productTags::of);
    }
}
