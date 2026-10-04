package com.grandis.nova.preorder.preorder;

/**
 * 예약 상태를 움직이는 사건과 그 사건이 원장에 남길 값. 사건마다 필요한 값을 타입이 함께 갖는다 —
 * 외부 번호 없는 등록 확인처럼 값이 빠진 사건을 만들 수 없다. 다음 상태는 {@link PreorderStatus#next} 가 정한다.
 */
public sealed interface PreorderFact {

    PreorderTrigger trigger();

    EventActor actor();

    /** 이력에 남길 사유. 없으면 null. */
    String reason();

    /** worker 가 외부 등록을 마쳤다. */
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

    /** 사용자 · 관리자 · 만료 · 회차 취소로 취소를 시작한다. */
    record CancelRequested(EventActor actor, String reason) implements PreorderFact {

        @Override
        public PreorderTrigger trigger() {
            return PreorderTrigger.CANCEL_REQUESTED;
        }
    }

    /** order 가 취소를 거절했다(배송 시작 · 만료 경합의 결제). */
    record CancelRejected(String reason) implements PreorderFact {

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
