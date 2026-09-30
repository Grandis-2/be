package com.grandis.nova.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 결제. 토스 연동 · 결제 원장 · 결과 불명 복구 · 환불.
 * 결제 대상(주문 · 응모)의 주인은 다른 서비스다 — 이 서비스는 대상의 상태를 모르고, 결제 결과만 기록하고 알린다.
 * 사용자 요청을 직접 받지 않는 내부 서비스다(order · draw 가 부른다).
 */
@SpringBootApplication(scanBasePackages = "com.grandis.nova")
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
