package com.grandis.nova.waitingroom.relay;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 접수 전달 지표. 전달 지연 · 상태 코드는 게이트웨이 자동 지표가 센다. */
@Component
class RelayMetrics {

    private final MeterRegistry registry;

    RelayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 입장권 확인 결과. FORWARDED · FORWARDED_EXPIRED · 거절 코드. */
    void checked(String outcome) {
        Counter.builder("waitingroom.relay.tickets").tag("outcome", outcome).register(registry).increment();
    }

    /** 접수 응답에서 보고 따른 것. SALE_CLOSED · ADMISSION_TICKET_STALE · ADMISSION_TICKET_USED. */
    void observed(String code) {
        Counter.builder("waitingroom.relay.observed").tag("code", code).register(registry).increment();
    }
}
