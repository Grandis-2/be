package com.grandis.nova.payment.config;

import com.grandis.nova.payment.confirm.PendingCaptureExpiry;
import com.grandis.nova.payment.recovery.RecoveryWorker;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * payment 의 주기 작업 — 결과 불명 복구와 시작하지 않은 결제창 만료. 둘 다 여러 인스턴스에서 돌아도 안전하다(복구는 SKIP LOCKED +
 * 리스, 만료는 조건부 UPDATE). 주기는 앞 실행이 끝난 뒤부터 잰다(겹치지 않는다).
 *
 * 실행기는 Boot 기본 스케줄러다(spring.task.scheduling.pool.size — 둘이 서로 밀리지 않게 2). 아웃박스 릴레이는 자체 실행기라 여기에
 * 섞이지 않는다. 켜고 끄는 것은 {@link PaymentJobsConfig}.
 */
public class PaymentJobs {

    private final RecoveryWorker recovery;
    private final PendingCaptureExpiry expiry;

    PaymentJobs(RecoveryWorker recovery, PendingCaptureExpiry expiry) {
        this.recovery = recovery;
        this.expiry = expiry;
    }

    /** 복구 대상의 가장 짧은 대기는 재시도 10초 · 리스 70초다. 5초면 그보다 늦지 않게 집는다. */
    @Scheduled(fixedDelayString = "${nova.payment.recovery.interval:5s}")
    void recover() {
        recovery.recoverDue();
    }

    /** 만료 기준이 분 단위(35분)라 1분 늦어도 상관없다. */
    @Scheduled(fixedDelayString = "${nova.payment.expiry.interval:60s}")
    void expire() {
        expiry.expireDue();
    }
}
