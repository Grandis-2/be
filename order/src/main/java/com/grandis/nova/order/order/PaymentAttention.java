package com.grandis.nova.order.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 결제 · 환불 결과를 반영하다 사람이 봐야 하는 경우를 알아챈 횟수(order.payment.attention, 사유별). 각 자리의 ERROR 와 함께 센다 — 경보는
 * 이 증가로 건다. 사유마다 처음부터 0 으로 등록한다 — 첫 증가 전에 시계열이 없으면 increase() 가 그 첫 번을 놓친다.
 * 같은 결과를 다시 받으면 다시 센다 — 사건 수가 아니라 알아챈 횟수다.
 */
@Component
public class PaymentAttention {

    static final String METER = "order.payment.attention";

    /** 태그 값은 이름을 바꾸면 경보 · 대시보드가 끊기므로 고정한다. */
    public enum Reason {
        /**
         * 승인이 왔는데 주문이 받지 못했고 자동 환불 대상도 아니다 — 결제창이 취소할 때 남긴 것과 다르거나, 결과를 모른 채 취소한 주문이 아니다
         * (상태가 어긋났다). 돈이 나간 채 주문이 결제되지 않았다.
         */
        UNMATCHED_APPROVAL,
        /** 결과를 모른 채 취소한 주문에 늦게 온 승인(결과 이벤트)의 금액이 주문 총액과 달라 자동 환불하지 않았다. 돈이 나간 채다. */
        AMOUNT_MISMATCH,
        /** 늦은 승인의 자동 환불이 확정 실패했다 — 취소된 주문의 돈이 돌아가지 않았다. */
        AUTO_REFUND_FAILED,
        /** 관리자가 환불을 다시 보냈는데(payment 는 새 거래를 열었다) 주문에 적지 못했다 — 주문의 실패 표시 · 이력이 payment 와 다르다. */
        REFUND_RETRY_UNRECORDED
    }

    private final Map<Reason, Counter> counters = new EnumMap<>(Reason.class);

    public PaymentAttention(MeterRegistry registry) {
        for (Reason reason : Reason.values()) {
            counters.put(reason, Counter.builder(METER).tag("reason", reason.name())
                    .description("결제 · 환불 결과 반영에서 사람이 봐야 하는 경우").register(registry));
        }
    }

    public void record(Reason reason) {
        counters.get(reason).increment();
    }
}
