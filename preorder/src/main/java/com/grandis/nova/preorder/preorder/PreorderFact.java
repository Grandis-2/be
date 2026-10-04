package com.grandis.nova.preorder.preorder;

import java.time.Instant;

/**
 * 예약 상태를 움직이는 사건과 그 사건이 원장에 남길 값. 사건마다 필요한 값을 타입이 함께 갖는다 —
 * 등록 확인에 외부 번호를 빠뜨리는 것 같은 호출을 컴파일러가 잡는다. 다음 상태는 {@link PreorderStatus#next} 가 정한다.
 */
public sealed interface PreorderFact {

    PreorderTrigger trigger();

    EventActor actor();

    /** 이력에 남길 사유. 없으면 null. */
    String reason();

    /** worker 가 외부 등록을 마쳤다. 취소 중에 늦게 도착하면 무시된다 — 취소된 예약을 되살리지 않는다(외부는 CANCEL 작업이 정리). */
    record RegisterConfirmed(String externalReference) implements PreorderFact {

        @Override
        public PreorderTrigger trigger() {
            return PreorderTrigger.REGISTER_CONFIRMED;
        }

        @Override
        public EventActor actor() {
            return EventActor.SYSTEM;
        }

        @Override
        public String reason() {
            return null;
        }
    }

    /** order 가 사전예약 주문을 만들었다. 화면의 "결제 진행 중" 표시용 시각만 남는다. */
    record PaymentStarted(Instant startedAt) implements PreorderFact {

        @Override
        public PreorderTrigger trigger() {
            return PreorderTrigger.PAYMENT_STARTED;
        }

        @Override
        public EventActor actor() {
            return EventActor.SYSTEM;
        }

        @Override
        public String reason() {
            return null;
        }
    }

    /** order 가 결제를 승인했다. 결제 가능 상태면 예약 확정, 취소 중이면 시각만 남긴다(거절되면 확정으로 돌아갈 근거). */
    record PaymentConfirmed(Instant paidAt) implements PreorderFact {

        @Override
        public PreorderTrigger trigger() {
            return PreorderTrigger.PAYMENT_CONFIRMED;
        }

        @Override
        public EventActor actor() {
            return EventActor.SYSTEM;
        }

        @Override
        public String reason() {
            return null;
        }
    }

    /** 사용자 · 관리자 · 만료 · 회차 취소로 취소를 시작한다. */
    record CancelRequested(EventActor actor, String reason) implements PreorderFact {

        @Override
        public PreorderTrigger trigger() {
            return PreorderTrigger.CANCEL_REQUESTED;
        }
    }

    /**
     * order 가 취소를 거절했다(배송 시작 · 만료 경합의 결제).
     *
     * @param paidAt 결제된 주문이라 거절했으면 결제 시각, 아니면 null. 결제 확인 이벤트보다 먼저 와도 예약 확정으로 돌아가게 한다
     */
    record CancelRejected(String reason, Instant paidAt) implements PreorderFact {

        @Override
        public PreorderTrigger trigger() {
            return PreorderTrigger.CANCEL_REJECTED;
        }

        @Override
        public EventActor actor() {
            return EventActor.SYSTEM;
        }
    }

    /** 외부 취소와 (있으면) 주문 취소가 모두 끝났다. */
    record CancelCompleted() implements PreorderFact {

        @Override
        public PreorderTrigger trigger() {
            return PreorderTrigger.CANCEL_COMPLETED;
        }

        @Override
        public EventActor actor() {
            return EventActor.SYSTEM;
        }

        @Override
        public String reason() {
            return null;
        }
    }
}
