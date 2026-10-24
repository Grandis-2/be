package com.grandis.nova.order.order.domain.enums;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.grandis.nova.order.order.domain.enums.OrderStatus.AUTHORIZING;
import static com.grandis.nova.order.order.domain.enums.OrderStatus.AWAITING_PAYMENT;
import static com.grandis.nova.order.order.domain.enums.OrderTrigger.CANCEL_REQUESTED;
import static com.grandis.nova.order.order.domain.enums.OrderTrigger.PAYMENT_APPROVED;
import static com.grandis.nova.order.order.domain.enums.OrderTrigger.PAYMENT_UNCONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 장부 대조의 기대 수량은 주문 상태의 분류(OrderStatus.stockHold)로 센다. 재고는 전이와 같은 트랜잭션에서만 옮겨지므로, 재고를 옮기지
 * 않는 전이에서 분류가 바뀌면 장부는 그대로인데 기대만 바뀌어 정상 주문이 어긋남으로 보인다. 상태 머신의 모든 (상태, 사건) 쌍을 본다.
 *
 * 재고를 옮기는 전이는 아래 MOVES 셋뿐이다(장바구니 · 드로우 주문 — 사전예약 주문은 재고가 없어 대조에서 빠진다). 전이를 더하거나 재고 이동을
 * 붙이면(결제 후 취소의 판매 되돌리기 등) 여기와 대조 식을 함께 고친다.
 */
class OrderStatusStockHoldTest {

    /** 재고를 옮기는 전이 → 옮긴 뒤 대조가 세야 하는 몫. 미결제 취소 · 결과를 모른 승인 중 취소는 확보를 돌려주며 반환 표식을 적는다. */
    static final Map<OrderStatus, Map<OrderTrigger, Counted>> MOVES = Map.of(
            AWAITING_PAYMENT, Map.of(CANCEL_REQUESTED, new Counted(StockHold.NONE, true)),
            AUTHORIZING, Map.of(PAYMENT_APPROVED, new Counted(StockHold.SOLD, false),
                    PAYMENT_UNCONFIRMED, new Counted(StockHold.NONE, true)));

    static Stream<Arguments> transitions() {
        return Stream.of(OrderStatus.values()).flatMap(status -> Stream.of(OrderTrigger.values())
                .flatMap(trigger -> status.next(trigger).stream().map(next -> Arguments.of(status, trigger, next))));
    }

    @ParameterizedTest(name = "{0} + {1} → {2}")
    @MethodSource("transitions")
    void onlyStockMovingTransitionsChangeWhatIsCounted(OrderStatus from, OrderTrigger trigger, OrderStatus to) {
        Optional<Counted> moved = Optional.ofNullable(MOVES.getOrDefault(from, Map.of()).get(trigger));

        if (moved.isPresent()) {
            assertThat(counted(from, false)).as("재고를 옮기는 전이는 확보에서 시작한다").isEqualTo(StockHold.RESERVED);
            assertThat(counted(to, moved.get().released())).isEqualTo(moved.get().hold());
        } else {
            assertThat(counted(to, false)).as("재고를 옮기지 않는 전이").isEqualTo(counted(from, false));
        }
    }

    /** 대조가 세는 몫 — NONE 은 반환 표식이 없으면 판매로 센다(JdbcStockExpectations 의 식). */
    static StockHold counted(OrderStatus status, boolean released) {
        StockHold hold = status.stockHold();
        return hold == StockHold.NONE && !released ? StockHold.SOLD : hold;
    }

    record Counted(StockHold hold, boolean released) {
    }
}
