package com.grandis.nova.payment.confirm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 호출자가 승인 중으로 바꿨는데 승인 요청이 payment 에 닿지 않은 결제창(payment.confirm.unreached, 결과별). {@link ConfirmHandover} 가 센다.
 * 넘긴 결제창의 첫 승인은 복구가 보내므로 {@link ConfirmOutcomeMetrics} 에는 잡히지 않는다.
 *
 * 두 결과를 처음부터 0 으로 등록한다 — 첫 증가 전에 시계열이 없으면 increase() 가 그 첫 번을 놓친다.
 */
@Component
public class UnreachedConfirmMetrics {

    static final String METER = "payment.confirm.unreached";

    /** 태그 값은 이름을 바꾸면 경보 · 대시보드가 끊기므로 고정한다. */
    enum Result {
        /** 복구에 넘겼다. 복구가 첫 승인을 보낸다. */
        HANDED_OVER,
        /** 시작 전이었지만 토스 승인 창(확보 뒤 10분)이 지나 넘기지 않았다. 만료가 닫는다. */
        TOO_LATE
    }

    private final Map<Result, Counter> counters = new EnumMap<>(Result.class);

    public UnreachedConfirmMetrics(MeterRegistry registry) {
        for (Result result : Result.values()) {
            counters.put(result, Counter.builder(METER).tag("result", result.name())
                    .description("승인 중으로 바꿨는데 승인 요청이 payment 에 닿지 않은 결제창").register(registry));
        }
    }

    void record(Result result) {
        counters.get(result).increment();
    }
}
