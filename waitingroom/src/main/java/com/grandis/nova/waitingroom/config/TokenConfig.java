package com.grandis.nova.waitingroom.config;

import com.grandis.nova.waitingroom.domain.queue.AdmissionTicket;
import com.grandis.nova.waitingroom.domain.queue.QueueToken;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 키가 없거나 짧으면 기동을 막는다 — 서명 없이 입장권을 낼 수는 없다. */
@Configuration(proxyBeanMethods = false)
class TokenConfig {

    @Bean
    AdmissionTicket admissionTicket(TokenProperties properties) {
        return AdmissionTicket.of(properties.secret(), properties.previous(), properties.rolloutEndsAt());
    }

    @Bean
    QueueToken queueToken(TokenProperties properties) {
        return QueueToken.of(properties.secret(), properties.previous(), properties.rolloutEndsAt());
    }
}
