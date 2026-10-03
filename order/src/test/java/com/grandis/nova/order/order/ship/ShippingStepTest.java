package com.grandis.nova.order.order.ship;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 단계가 들고 있는 출발 · 도착 상태가 상태 머신과 맞는가. 원장 전제(expectedFrom)를 출발 상태 하나로 넘기는 근거가
 * "그 사건은 그 상태에서만 받아들여진다" 라서 모든 상태를 대 본다 — 상태 머신이 바뀌어 다른 상태에서도 받게 되면 여기서 깨진다.
 */
class ShippingStepTest {

    @ParameterizedTest
    @EnumSource(ShippingStep.class)
    void onlyTheSourceStatusAcceptsTheStep(ShippingStep step) {
        for (OrderStatus status : OrderStatus.values()) {
            Optional<OrderStatus> next = status.next(step.trigger());
            if (status == step.from()) {
                assertThat(next).as("%s 에서 %s", status, step).contains(step.to());
            } else {
                assertThat(next).as("%s 에서 %s", status, step).isEmpty();
            }
        }
    }
}
