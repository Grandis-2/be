package com.grandis.nova.order.order;

import com.grandis.nova.common.jpa.StorageClock;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.PreorderProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static com.grandis.nova.order.support.OrderFixtures.preorderCommand;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장한 시각과 DB 에 들어간 시각이 같은가. Linux JVM 처럼 나노초를 주는 시계로 돌린다 —
 * macOS 시계는 마이크로초라 이 어긋남을 로컬에서 볼 수 없다.
 */
@OrderIntegrationTest
@Transactional
class StoredTimestampTest {

    /** 마이크로초 아래가 .9 — 반올림되면 다음 마이크로초가 된다. */
    static final Instant NANOS = Instant.parse("2026-01-01T00:00:00.000000900Z");

    /*
     * 운영 저장 시계 빈(storageClock)을 이름으로 바꿔 끼운다. 보정은 운영과 같은 함수로 하고 바탕 시계만 나노초 고정이다.
     * @Primary 시계를 하나 더 두는 방식은 쓸 수 없다 — common:security 의 clock 과 함께 Clock 빈이 셋, @Primary 가 둘이 된다.
     */
    @TestBean(name = "storageClock", methodName = "nanosecondClock")
    Clock storageClock;

    static Clock nanosecondClock() {
        return StorageClock.atStorageResolution(Clock.fixed(NANOS, ZoneOffset.UTC));
    }

    @Autowired
    OrderLedger ledger;

    @Autowired
    OrderReader reader;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void timestampsReturnedByLedgerEqualWhatDatabaseStored() {
        OrderFixtures fixtures = new OrderFixtures(jdbcTemplate);
        PreorderProduct product = fixtures.preorderProduct();
        UUID customerId = fixtures.customer();
        UUID preorderId = fixtures.payablePreorder(customerId, product, 1);

        Order placed = ledger.place(preorderCommand(customerId, preorderId, product).toDraft(), EventCause.user());
        ledger.fire(placed.id(), OrderTrigger.CANCEL_REQUESTED, EnumSet.of(OrderStatus.AWAITING_PAYMENT),
                EventCause.system("USER"));
        entityManager.clear();

        Order loaded = reader.findById(placed.id()).orElseThrow();
        assertThat(loaded.createdAt()).isEqualTo(placed.createdAt());
        List<Instant> eventTimes = jdbcTemplate.query(
                "SELECT created_at FROM order_events WHERE order_id = ? ORDER BY event_sequence",
                // DB 는 UTC 벽시계 시각을 담는다. getTimestamp 는 JVM 시간대로 읽으므로 쓰지 않는다
                (rs, n) -> rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC), bytes(placed.id()));
        // 생성 · 취소 두 이력이 번호 순으로, 둘 다 같은 시각(고정 시계)
        assertThat(eventTimes).containsExactly(placed.createdAt(), placed.createdAt());
        assertThat(loaded.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(loaded.updatedAt()).isEqualTo(placed.createdAt());
        assertThat(placed.createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }
}
