package com.grandis.nova.payment.config;

import com.grandis.nova.payment.confirm.PendingCaptureExpiry;
import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.recovery.RecoveryWorker;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * payment 의 주기 작업 — 승인 결과 불명 복구, 환불 실행 · 복구, 시작하지 않은 결제창 만료. 모두 여러 인스턴스에서 돌아도 안전하다
 * (복구는 SKIP LOCKED + 리스, 만료는 조건부 UPDATE). 주기는 앞 실행이 끝난 뒤부터 잰다(겹치지 않는다).
 *
 * 승인과 환불은 따로 돈다 — 환불은 건마다 토스 호출이 최대 셋(취소 · 조회 · 교체 후 재전송, 최악 약 190초)이고 서두를 시각이 없어,
 * 같은 실행에 묶으면 적체가 승인 창(10분)이 걸린 승인 복구를 밀어낸다. 실행기는 Boot 기본 스케줄러이고 셋이 서로 밀리지 않게 스레드를
 * 3 이상으로 둔다({@link PaymentJobsConfig}).
 * 아웃박스 릴레이는 자체 실행기라 여기에 섞이지 않는다. 켜고 끄는 것은 {@link PaymentJobsConfig}.
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
        recovery.recoverDue(TransactionType.CAPTURE);
    }

    /** 열린 환불의 첫 전송도 여기서 한다 — 5초면 요청 뒤 곧 보낸다. */
    @Scheduled(fixedDelayString = "${nova.payment.refund.interval:5s}")
    void refund() {
        recovery.recoverDue(TransactionType.REFUND);
    }

    /** 만료 기준이 분 단위(35분)라 1분 늦어도 상관없다. */
    @Scheduled(fixedDelayString = "${nova.payment.expiry.interval:60s}")
    void expire() {
        expiry.expireDue();
    }
}
