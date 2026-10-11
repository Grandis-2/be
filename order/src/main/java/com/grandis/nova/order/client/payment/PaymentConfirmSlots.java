package com.grandis.nova.order.client.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * payment 승인을 기다리는 요청 수 상한(인스턴스당). 주문 결제와 응모비 결제가 같은 상한을 나눠 쓴다 — 승인은 payment 응답을 최대 70초
 * 기다리므로, 따로 두면 토스가 느릴 때 묶이는 요청 스레드가 상한의 두 배가 된다.
 */
@Component
public class PaymentConfirmSlots {

    private final Semaphore inFlight;

    /** @param maxConcurrency 인스턴스당 동시에 payment 승인을 기다리는 요청 수 상한(Tomcat 요청 스레드 200 중 일부) */
    PaymentConfirmSlots(@Value("${nova.payment-confirm.max-concurrency:50}") int maxConcurrency) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("nova.payment-confirm.max-concurrency 는 1 이상이어야 한다: " + maxConcurrency);
        }
        this.inFlight = new Semaphore(maxConcurrency);
    }

    /** 자리를 잡았으면 true — 그때만 {@link #release()} 한다. */
    public boolean tryAcquire() {
        return inFlight.tryAcquire();
    }

    public void release() {
        inFlight.release();
    }
}
