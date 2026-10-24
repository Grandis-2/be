package com.grandis.nova.order.order.cancel;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.domain.repository.ExpiredOrderPosition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 결과를 모른 주문 취소 작업의 스위치 · 설정 범위 · 이어 읽기. 판정 · 반영은 UnconfirmedCartOrderExpiryTest 가 MySQL 로 본다.
 * 스위치는 새 취소만 멈춘다 — 이미 이렇게 취소한 주문의 늦은 승인 자동 환불(PaymentResults)은 이 작업을 거치지 않아 스위치와 상관없다
 * (UnconfirmedCartOrderExpiryTest 는 스위치를 끈 채 cancelDue 를 직접 불러 자동 환불까지 본다).
 */
class UnconfirmedCartOrderExpiryUnitTest {

    static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");

    final OrderReader reader = mock(OrderReader.class);
    final UnconfirmedCartOrderRelease release = mock(UnconfirmedCartOrderRelease.class);

    @Test
    void 꺼져_있으면_고르지도_않는다() {
        expiry(false).run();

        verifyNoInteractions(reader, release);
    }

    @Test
    void 켜져_있으면_만든_뒤_after_가_지난_주문을_고른다() {
        given(reader.findUnconfirmedAuthorizingOrders(any(), any(), anyInt())).willReturn(List.of());

        expiry(true).run();

        // 기한 = 만든 때 + 10분이므로, 만든 뒤 20분 = 기한 <= now - 10분
        verify(reader).findUnconfirmedAuthorizingOrders(eq(NOW.minus(Duration.ofMinutes(10))), eq(ExpiredOrderPosition.START),
                eq(UnconfirmedCartOrderExpiry.BATCH));
    }

    @Test
    void 쪽_경계를_넘어_이어_읽고_실패한_주문은_건너뛰고_계속한다() {
        Order failing = order(1, NOW.minusSeconds(900));
        Order second = order(2, NOW.minusSeconds(800));
        Order third = order(3, NOW.minusSeconds(700));
        given(reader.findUnconfirmedAuthorizingOrders(any(), eq(ExpiredOrderPosition.START), eq(2)))
                .willReturn(List.of(failing, second));
        ExpiredOrderPosition afterSecond = new ExpiredOrderPosition(NOW.minusSeconds(800), TestIds.id(2));
        given(reader.findUnconfirmedAuthorizingOrders(any(), eq(afterSecond), eq(2))).willReturn(List.of(third));
        given(release.cancel(eq(TestIds.id(1)), any())).willThrow(new IllegalStateException("재고 어긋남"));
        given(release.cancel(eq(TestIds.id(2)), any())).willReturn(new OrderTransition(true, OrderStatus.CANCELED));
        given(release.cancel(eq(TestIds.id(3)), any())).willReturn(new OrderTransition(true, OrderStatus.CANCELED));

        assertThat(expiry(true).cancelDue(NOW, 2)).isEqualTo(2);

        verify(release).cancel(eq(TestIds.id(3)), any());
    }

    @Test
    void after_는_결제_기한보다_길고_interval_은_0_보다_커야_한다() {
        assertThatThrownBy(() -> new UnconfirmedPaymentProperties(false, Duration.ofMinutes(10), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UnconfirmedPaymentProperties(false, Duration.ofMinutes(5), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UnconfirmedPaymentProperties(false, Duration.ofMinutes(20), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UnconfirmedPaymentProperties(false, Duration.ofMinutes(20), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new UnconfirmedPaymentProperties(false, Duration.ofMinutes(10).plusSeconds(1), Duration.ofSeconds(1))
                .dueBefore(NOW)).isEqualTo(NOW.minusSeconds(1));
    }

    private UnconfirmedCartOrderExpiry expiry(boolean enabled) {
        return new UnconfirmedCartOrderExpiry(reader, release,
                new UnconfirmedPaymentProperties(enabled, Duration.ofMinutes(20), Duration.ofSeconds(30)),
                mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Order order(int id, Instant due) {
        Order order = mock(Order.class);
        UUID orderId = TestIds.id(id);
        given(order.id()).willReturn(orderId);
        given(order.paymentDueAt()).willReturn(due);
        return order;
    }
}
