package com.grandis.nova.order.order.vo;

import com.grandis.nova.order.order.domain.enums.EventActor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventCauseTest {

    @Test
    void adminRequiresReason() {
        assertThatThrownBy(() -> EventCause.admin(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventCause.admin(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(EventCause.admin("전화 주문").reason()).isEqualTo("전화 주문");
    }

    @Test
    void blankReasonMeansNoReason() {
        assertThat(EventCause.system(" ").reason()).isNull();
        assertThat(EventCause.user()).isEqualTo(new EventCause(EventActor.USER, null));
    }

    // order_events.reason 은 500자다. 넘으면 상태를 바꾼 뒤에야 DB 가 거부하지 않도록 앞에서 막는다.
    @Test
    void reasonIsAtMostFiveHundredCharacters() {
        assertThat(EventCause.system("가".repeat(500)).reason()).hasSize(500);
        assertThatThrownBy(() -> EventCause.system("가".repeat(501))).isInstanceOf(IllegalArgumentException.class);
    }
}
