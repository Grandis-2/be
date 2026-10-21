package com.grandis.nova.order.order.metrics;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 지표의 상태 — 아직 못 읽음(NaN), 읽음(없는 상태는 0), 읽은 뒤 실패(앞의 값). */
class OrderStatusGaugesUnitTest {

    final OrderReader orders = mock(OrderReader.class);
    final MeterRegistry registry = new SimpleMeterRegistry();
    final OrderStatusGauges gauges = new OrderStatusGauges(orders, registry);

    @Test
    @DisplayName("처음 읽기 전에는 NaN — 0 이면 열린 경보가 배포 때 해소됐다 다시 열린다")
    void unknownBeforeTheFirstRead() {
        assertThat(gauge(OrderStatus.PREPARING_ITEMS)).isNaN();
        assertThat(gauge(OrderStatus.READY_TO_SHIP)).isNaN();
    }

    @Test
    @DisplayName("읽으면 상태별 수, 주문이 없는 상태는 0 · 읽은 뒤 실패하면 앞의 값을 둔다")
    void countsAndKeepsTheLastValueOnFailure() {
        when(orders.countByStatus(any())).thenReturn(Map.of(OrderStatus.PREPARING_ITEMS, 45L));
        gauges.refresh();
        assertThat(gauge(OrderStatus.PREPARING_ITEMS)).isEqualTo(45);
        assertThat(gauge(OrderStatus.READY_TO_SHIP)).isZero();

        when(orders.countByStatus(any())).thenThrow(new IllegalStateException("DB 끊김"));
        gauges.refresh();

        assertThat(gauge(OrderStatus.PREPARING_ITEMS)).isEqualTo(45);
        assertThat(gauge(OrderStatus.READY_TO_SHIP)).isZero();
    }

    private double gauge(OrderStatus status) {
        return registry.get(OrderStatusGauges.METRIC).tag("status", status.name()).gauge().value();
    }
}
