package com.grandis.nova.order.order.query;

import com.grandis.nova.common.redis.cache.ReadCache;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.domain.repository.PaymentUnconfirmedCounts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 상태별 주문 수 — 모든 상태를 순서대로(없는 상태 0) · 센 시각, 전체 수에는 센 시각 기준 1시간 · 24시간의 결제 확인 실패 건수, 켠 캐시는 TTL
 * 동안 전체 수를 다시 세지 않는다, 상품별 수는 캐시하지 않고 결제 확인 실패 건수가 없다.
 */
class OrderStatusCountsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-21T09:00:00Z");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);
    private static LettuceConnectionFactory factory;

    @BeforeAll
    static void connect() {
        REDIS.start();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    final OrderReader reader = mock(OrderReader.class);
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @DisplayName("모든 상태를 상태 순서로, 주문이 없는 상태는 0, 센 시각 — 모든 상태를 묻는다")
    void everyStatusInOrderWithZeros() {
        when(reader.countByStatus(any())).thenReturn(Map.of(OrderStatus.SHIPPED, 3L, OrderStatus.AWAITING_PAYMENT, 1L));

        var snapshot = new OrderStatusCounts(reader, ReadCache.off(JSON, "order"), clock).all();

        assertThat(snapshot.byStatus().keySet()).containsExactly(OrderStatus.values());
        assertThat(snapshot.byStatus()).containsEntry(OrderStatus.SHIPPED, 3L).containsEntry(OrderStatus.AWAITING_PAYMENT, 1L)
                .containsEntry(OrderStatus.CANCELED, 0L);
        assertThat(snapshot.countedAt()).isEqualTo(NOW);
        verify(reader).countByStatus(EnumSet.allOf(OrderStatus.class));
    }

    @Test
    @DisplayName("전체 수 — 센 시각에서 1시간 · 24시간 전부터의 결제 확인 실패 건수를 붙인다")
    void allCarriesPaymentUnconfirmedOfLastHourAndDay() {
        when(reader.countPaymentUnconfirmed(NOW.minusSeconds(3_600), NOW.minusSeconds(86_400))).thenReturn(new PaymentUnconfirmedCounts(2, 9));

        var snapshot = new OrderStatusCounts(reader, ReadCache.off(JSON, "order"), clock).all();

        assertThat(snapshot.paymentUnconfirmed()).isEqualTo(new PaymentUnconfirmedCounts(2, 9));
    }

    @Test
    @DisplayName("켠 캐시 — 두 번째는 Redis 에서 같은 값 · 같은 센 시각(읽기 · 쓰기가 맞물린다), DB 는 한 번만 센다")
    void cachedWithinTtl() {
        when(reader.countByStatus(any())).thenReturn(Map.of(OrderStatus.DELIVERED, 7L), Map.of(OrderStatus.DELIVERED, 8L));
        when(reader.countPaymentUnconfirmed(any(), any())).thenReturn(new PaymentUnconfirmedCounts(1, 3), new PaymentUnconfirmedCounts(4, 5));
        Clock later = mock(Clock.class);
        when(later.instant()).thenReturn(NOW, NOW.plusSeconds(1));
        OrderStatusCounts counts = new OrderStatusCounts(reader, cacheOnRedis(), later);

        var first = counts.all();
        var second = counts.all();

        assertThat(second).isEqualTo(first);
        assertThat(second.byStatus()).containsEntry(OrderStatus.DELIVERED, 7L);
        assertThat(second.byStatus().keySet()).containsExactly(OrderStatus.values());
        assertThat(second.paymentUnconfirmed()).isEqualTo(new PaymentUnconfirmedCounts(1, 3));
        assertThat(second.countedAt()).isEqualTo(NOW);
        verify(reader, times(1)).countByStatus(any());
        verify(reader, times(1)).countPaymentUnconfirmed(any(), any());
    }

    @Test
    @DisplayName("상품별 수 — 켠 캐시여도 부를 때마다 센다(키가 상품 id 마다 늘지 않게), 결제 확인 실패 건수는 세지 않는다")
    void productCountsAreNotCached() {
        UUID productId = UUID.randomUUID();
        when(reader.countByStatusOfProduct(productId)).thenReturn(Map.of(OrderStatus.SHIPPED, 1L), Map.of(OrderStatus.SHIPPED, 2L));
        OrderStatusCounts counts = new OrderStatusCounts(reader, cacheOnRedis(), clock);

        assertThat(counts.ofProduct(productId).byStatus()).containsEntry(OrderStatus.SHIPPED, 1L).containsEntry(OrderStatus.CANCELED, 0L);
        var second = counts.ofProduct(productId);
        assertThat(second.byStatus()).containsEntry(OrderStatus.SHIPPED, 2L);
        assertThat(second.paymentUnconfirmed()).isNull();
        verify(reader, times(2)).countByStatusOfProduct(productId);
        verify(reader, never()).countPaymentUnconfirmed(any(), any());
    }

    /** 시험마다 서비스 이름을 달리해 키가 겹치지 않게 한다. */
    private static ReadCache cacheOnRedis() {
        return new ReadCache(new StringRedisTemplate(factory), JSON, "order-" + UUID.randomUUID());
    }
}
