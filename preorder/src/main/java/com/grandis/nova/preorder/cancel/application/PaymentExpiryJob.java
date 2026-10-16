package com.grandis.nova.preorder.cancel.application;

import com.grandis.nova.preorder.cancel.ExpiryCancelService;
import com.grandis.nova.preorder.preorder.Preorders;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * 결제 기한이 지난 예약의 만료 취소를 시작한다. 기한은 누가 누르는 동작이 아니라 시각이 지나는 것이라 주기적으로 찾는다.
 * 인스턴스마다 돌지만 만료는 예약 행을 잠근 뒤 다시 확인하므로 한 번만 시작된다. 한 건마다 따로 커밋하고,
 * 실패한 건은 로그만 남기고 다음 주기에 다시 집는다 — 한 건이 계속 실패해도 나머지를 막지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "nova.preorder-expiry", name = "enabled", havingValue = "true")
class PaymentExpiryJob {

    static final String STARTED_METRIC = "preorder.expiry.started";
    static final String FAILED_METRIC = "preorder.expiry.failed";

    private static final Logger log = LoggerFactory.getLogger(PaymentExpiryJob.class);

    private final Preorders preorders;
    private final ExpiryCancelService expiryCancelService;
    private final PaymentExpiryProperties properties;
    private final Counter started;
    private final Counter failed;
    private final Clock clock;

    PaymentExpiryJob(Preorders preorders, ExpiryCancelService expiryCancelService, PaymentExpiryProperties properties,
                     MeterRegistry meterRegistry, Clock clock) {
        this.preorders = preorders;
        this.expiryCancelService = expiryCancelService;
        this.properties = properties;
        this.started = meterRegistry.counter(STARTED_METRIC);
        this.failed = meterRegistry.counter(FAILED_METRIC);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${nova.preorder-expiry.interval:1m}",
            initialDelayString = "${nova.preorder-expiry.interval:1m}")
    void run() {
        expireOverdue();
    }

    /** @return 이번 주기에 취소를 시작한 수 */
    int expireOverdue() {
        List<String> tokens = preorders.findPaymentOverdue(clock.instant(), properties.batchSize());
        int count = 0;
        for (String token : tokens) {
            try {
                if (expiryCancelService.expire(token)) {
                    started.increment();
                    count++;
                }
            } catch (RuntimeException e) {
                failed.increment();
                log.warn("결제 기한 만료를 시작하지 못했다 — 다음 주기에 다시 집는다 preorderId={}", token, e);
            }
        }
        if (count > 0) {
            log.info("결제 기한 만료 시작 {}건 (후보 {}건)", count, tokens.size());
        }
        return count;
    }
}
