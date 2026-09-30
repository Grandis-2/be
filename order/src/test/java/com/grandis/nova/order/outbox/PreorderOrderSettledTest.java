package com.grandis.nova.order.outbox;

import com.grandis.nova.order.outbox.PreorderOrderSettled.RejectReason;
import com.grandis.nova.order.outbox.PreorderOrderSettled.Result;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreorderOrderSettledTest {

    static final Long PREORDER_ID = 42L;
    static final String PREORDER_UUID = "9f1c2d3e-0000-0000-0000-000000000000";

    @Test
    void factoriesSetResultAndReason() {
        assertThat(PreorderOrderSettled.noOrder(PREORDER_ID, PREORDER_UUID, 3L))
                .extracting(PreorderOrderSettled::result, PreorderOrderSettled::reason)
                .containsExactly(Result.NO_ORDER, null);
        assertThat(PreorderOrderSettled.canceled(PREORDER_ID, PREORDER_UUID, 3L))
                .extracting(PreorderOrderSettled::result, PreorderOrderSettled::reason)
                .containsExactly(Result.CANCELED, null);
        assertThat(PreorderOrderSettled.rejected(PREORDER_ID, PREORDER_UUID, RejectReason.PAID, 3L))
                .extracting(PreorderOrderSettled::result, PreorderOrderSettled::reason)
                .containsExactly(Result.REJECTED, RejectReason.PAID);
    }

    @Test
    void envelopeIsPreorderAggregateWithInternalId() {
        PreorderOrderSettled message = PreorderOrderSettled.canceled(PREORDER_ID, PREORDER_UUID, 3L);

        assertThat(message.eventType()).isEqualTo(OutboundEventType.PREORDER_ORDER_SETTLED);
        assertThat(message.aggregateType()).isEqualTo(AggregateType.PREORDER);
        assertThat(message.aggregateId()).isEqualTo(PREORDER_ID);
    }

    /** 받는 쪽은 cancelSequence 가 없으면 반영하지 않는다. order 가 커밋한 뒤라 되돌릴 수 없으므로 보내기 전에 막는다. */
    @Test
    void cancelSequenceIsRequired() {
        assertThatThrownBy(() -> PreorderOrderSettled.canceled(PREORDER_ID, PREORDER_UUID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cancelSequence");
    }

    @Test
    void preorderTokenIsRequired() {
        assertThatThrownBy(() -> PreorderOrderSettled.noOrder(PREORDER_ID, " ", 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PreorderOrderSettled.noOrder(PREORDER_ID, null, 3L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void internalIdIsRequiredForEnvelope() {
        assertThatThrownBy(() -> PreorderOrderSettled.noOrder(null, PREORDER_UUID, 3L))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void reasonOnlyWithRejection() {
        assertThatThrownBy(() -> new PreorderOrderSettled(PREORDER_ID, PREORDER_UUID, Result.REJECTED, null, 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreorderOrderSettled(PREORDER_ID, PREORDER_UUID, Result.CANCELED, RejectReason.SHIPPED, 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PreorderOrderSettled.rejected(PREORDER_ID, PREORDER_UUID, null, 3L))
                .isInstanceOf(NullPointerException.class);
    }
}
