package com.grandis.nova.order.stock.admin;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.event.OrderEventDispatcher;
import com.grandis.nova.order.stock.domain.model.StockSetting;
import com.grandis.nova.order.stock.domain.repository.StockReader;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.StockProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willCallRealMethod;

/**
 * 판매 준비 이벤트(contracts/order-catalog-events.md)가 재고 행을 만드는 모든 길에서 같은 트랜잭션으로 적히는가 — 실제 아웃박스 표에서 본다.
 * 봉투 모양 · 목적지로 보내기는 OrderOutboxWiringTest 가 본다.
 */
@OrderIntegrationTest
class StockReadyEventTest {

    @Autowired
    AdminStockService service;

    @Autowired
    OrderEventDispatcher dispatcher;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    StockReader stockReader;

    OrderFixtures fixtures;
    StockProduct product;
    UUID first;
    UUID second;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        product = fixtures.inStockProduct(2);
        first = product.optionIds().get(0);
        second = product.optionIds().get(1);
    }

    @Test
    void 초기화가_행을_만들면_상품_aggregate_로_한_건_적는다() {
        service.initialize(product.productId(), List.of(new StockSetting(first, 5), new StockSetting(second, 0)));

        assertThat(readyEvents()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("aggregate_type", "PRODUCT")
                .containsEntry("payload", "{}"));
    }

    @Test
    void 설정이_새_행을_만들면_한_건_있는_행만_고치면_적지_않는다() {
        fixtures.stock(first, 10, 0, 0);

        service.set(product.productId(), List.of(new StockSetting(first, 7)));
        assertThat(readyEvents()).isEmpty();

        service.set(product.productId(), List.of(new StockSetting(first, 8), new StockSetting(second, 3)));
        assertThat(readyEvents()).hasSize(1);
    }

    /** 등록이 재개돼 같은 초기화가 다시 와도(행이 이미 다 있음) 더 적지 않는다. */
    @Test
    void 이미_다_있는_초기화는_적지_않는다() {
        List<StockSetting> settings = List.of(new StockSetting(first, 5), new StockSetting(second, 1));
        service.initialize(product.productId(), settings);
        service.initialize(product.productId(), settings);

        assertThat(readyEvents()).hasSize(1);
    }

    /** catalog 의 등록 이벤트 → 초기 재고(사전예약 → 일반 전환도 이 길이다). */
    @Test
    void 상품_등록_이벤트_처리도_한_건_적는다() {
        dispatcher.dispatch("""
                {"eventId":"%s","eventType":"IN_STOCK_PRODUCT_REGISTERED","aggregateType":"PRODUCT","aggregateId":"%s",
                 "occurredAt":"2026-10-17T03:00:00.123456Z",
                 "payload":{"items":[{"optionId":"%s","stockTotal":5},{"optionId":"%s","stockTotal":0}]}}
                """.formatted(UUID.randomUUID(), product.productId(), first, second));

        assertThat(readyEvents()).hasSize(1);
    }

    /**
     * 이벤트를 적은 뒤에 실패해도 함께 롤백된다. 결과 재조회(append 다음 단계)를 한 번 실패시킨다 — 원장의 읽기는 그 앞에서 한 번 지나간다.
     * 아래 두 롤백 시험은 append 앞에서 실패하므로 이것을 보지 못한다.
     */
    @Test
    void 이벤트를_적은_뒤에_실패하면_이벤트도_롤백된다() {
        willCallRealMethod().willThrow(new IllegalStateException("재조회 실패")).given(stockReader).findByOptionIds(any());

        assertThatThrownBy(() -> service.initialize(product.productId(), List.of(new StockSetting(first, 5))))
                .hasMessageContaining("재조회 실패");   // 저장소 프록시가 스프링 예외로 바꿔 던진다

        assertThat(readyEvents()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM option_inventories WHERE option_id = ?",
                Integer.class, bytes(first))).isZero();
    }

    /** 새 행과 함께 확보 아래로 줄이는 옵션이 있으면 요청 전체가 롤백된다 — 행도 이벤트도 남지 않는다. */
    @Test
    void 확보_아래로_줄여_롤백되면_적지_않는다() {
        fixtures.stock(first, 10, 3, 2);

        assertThatThrownBy(() -> service.set(product.productId(),
                List.of(new StockSetting(first, 4), new StockSetting(second, 5))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(OrderErrorCode.STOCK_BELOW_COMMITTED));

        assertThat(readyEvents()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM option_inventories WHERE option_id = ?",
                Integer.class, bytes(second))).isZero();
    }

    @Test
    void 그_상품의_옵션이_아니면_적지_않는다() {
        UUID foreign = fixtures.inStockProduct(1).optionIds().getFirst();

        assertThatThrownBy(() -> service.initialize(product.productId(),
                List.of(new StockSetting(first, 5), new StockSetting(foreign, 1))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED));

        assertThat(readyEvents()).isEmpty();
    }

    private List<Map<String, Object>> readyEvents() {
        return jdbcTemplate.queryForList("""
                SELECT aggregate_type, CAST(payload AS CHAR) AS payload FROM order_outbox_events
                 WHERE event_type = 'IN_STOCK_PRODUCT_READY' AND aggregate_id = ? ORDER BY id
                """, bytes(product.productId()));
    }
}
