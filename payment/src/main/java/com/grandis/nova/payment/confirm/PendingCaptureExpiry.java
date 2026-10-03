package com.grandis.nova.payment.confirm;

import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 한 번도 시작하지 않은 결제창(CAPTURE PENDING)을 만료(EXPIRED)로 닫고 대상에 거절(PAYMENT_EXPIRED)을 알린다.
 *
 * 두 경우를 함께 닫는다. 사용자가 결제창을 버렸으면 대상은 결제 대기 그대로라 알림을 무시한다. 대상이 승인 중으로 바뀌었는데 결제
 * 서비스가 시작 전에 멈췄으면(응답 유실 · 재시작) 이 알림이 대상을 결제 대기로 되돌린다 — 그 결제창의 승인 중일 때만(대상의 번호 가드).
 *
 * 돈은 움직이지 않는다: 결제사에 보낸 적 없는 행만 닫고, 시작과 같은 PENDING 조건이라 겹치면 한쪽만 된다. 결제창 인증만 된 결제는
 * 승인하지 않으면 청구되지 않는다. 기준을 넘긴 사용자의 승인 요청은 "결제창 만료" 거절을 받는다.
 */
@Component
public class PendingCaptureExpiry {

    private static final Logger log = LoggerFactory.getLogger(PendingCaptureExpiry.class);

    /**
     * 결제창을 연 뒤 이만큼 지나면 만료한다: 토스 결제 객체 수명 30분 + 여유 5분. 30분이 결제창을 연 때부터의 절대 수명이라는 가정이다
     * (인증 뒤 승인 창 10분이 따로 붙는다면 최대 약 40분까지 승인될 수 있다). 틀려도 돈은 움직이지 않는다 — 만료는 보낸 적 없는 행만
     * 닫고, 그 뒤의 승인 요청은 "결제창 만료" 로 거절돼 사용자가 다시 결제한다. 연 시각은 앱 시계(created_at)라 서버 시계 차만큼
     * 어긋나지만 여유 안이다. 대가: 시작 전에 멈춘 결제창의 대상이 최대 이만큼 결제 확인 중에 머문다.
     */
    public static final Duration OPENED_FOR = Duration.ofMinutes(35);

    /** 한 번에 닫는 최대 건수. 건마다 트랜잭션 하나다. */
    static final int BATCH = 100;

    private final PaymentTransactionReader reader;
    private final CaptureSettlement settlement;

    public PendingCaptureExpiry(PaymentTransactionReader reader, CaptureSettlement settlement) {
        this.reader = reader;
        this.settlement = settlement;
    }

    /** @return 만료한 건수 */
    public int expireDue() {
        int expired = 0;
        for (PaymentTransaction seen : reader.findExpirableCaptures(OPENED_FOR, BATCH)) {
            try {
                if (settlement.expire(seen, OPENED_FOR).isPresent()) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("결제창 만료 실패 — 다음 실행에서 다시 본다 transactionId={}", seen.id(), e);
            }
        }
        if (expired > 0) {
            log.info("시작하지 않은 결제창 만료 count={}", expired);
        }
        return expired;
    }
}
