package com.grandis.nova.order.client.payment;

import java.util.Objects;

/** payment 승인 호출의 결과. 부르는 쪽이 switch 로 모두 다룬다. */
public sealed interface PaymentConfirmation {

    record Approved() implements PaymentConfirmation {
    }

    record Declined(DeclineReason reason) implements PaymentConfirmation {

        public Declined {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** payment 가 처리 중이거나 결과를 모른다(결과 불명 · 응답을 못 받음). 확정되면 결과 이벤트가 온다. 200 "확인 중"으로 답한다. */
    record Pending() implements PaymentConfirmation {
    }

    /**
     * payment 가 "이 결제창은 앞으로도 시작될 수 없다" 고 확언했다(번호 없음 · 금액 불일치 · 미지원 대상). 대상을 결제 대기로
     * 되돌린 뒤 failure 로 답한다.
     */
    record NotStartable(RuntimeException failure) implements PaymentConfirmation {

        public NotStartable {
            Objects.requireNonNull(failure, "failure");
        }
    }

    /**
     * 이번 요청이 거절되거나 닿지 못했다(인증 · 교착 · 연결 실패 · 계약 밖 4xx). 이번 요청에 대한 신호일 뿐이라 앞선 요청이 같은
     * 결제창을 이미 시작했을 수 있다 — 대상은 그대로 두고 failure 로 답한다.
     */
    record Unanswered(RuntimeException failure) implements PaymentConfirmation {

        public Unanswered {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
