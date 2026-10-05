package com.grandis.nova.waitingroom.entry;

import com.grandis.nova.waitingroom.domain.admission.AdmissionDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 요청 경로 지표. 태그 값은 유한한 집합(판정 · 상태 이름)만 쓴다 — 모델 · 회원을 태그로 달지 않는다. */
@Component
public class EntryMetrics {

    private final MeterRegistry registry;

    EntryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void decision(AdmissionDecision decision) {
        Counter.builder("waitingroom.admission.decisions").tag("decision", decision.name()).register(registry).increment();
    }

    /** 진입 결과. ADMITTED · WAITING · 거절 코드. */
    void entry(String outcome) {
        Counter.builder("waitingroom.entry").tag("outcome", outcome).register(registry).increment();
    }

    /** 조회 결과. WAITING · ADMITTED · NOT_IN_QUEUE · SALE_CLOSED · 오류 코드. */
    void status(String outcome) {
        Counter.builder("waitingroom.status").tag("outcome", outcome).register(registry).increment();
    }
}
