package com.grandis.nova.order.order.place;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.cart.domain.model.CartOption;
import com.grandis.nova.order.cart.domain.model.CartSlot;
import com.grandis.nova.order.cart.domain.model.Unavailability;
import com.grandis.nova.order.order.command.PlaceOrderCommand;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.model.OrderDraft;
import com.grandis.nova.order.order.domain.model.OrderLine;
import com.grandis.nova.order.web.ValidationFailures;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 고른 줄을 catalog 의 지금 값과 대조하고 주문 초안으로 바꾼다 — 장바구니 주문 · 바로 구매 주문이 같은 판정 · 같은 금액 계산을 쓴다.
 * 판정 · 초안({@link #requireDistinctSlots} · {@link #draft})은 트랜잭션 전에 부른다 — 원장 안에서 던지면 트랜잭션이 rollback-only 가 된다.
 */
final class SelectionRules {

    /** 주문의 재고 부족 문구(명세 POST /orders). 같은 코드의 기본 문구는 장바구니 담기의 것이다. */
    static final String SHORTAGE_MESSAGE = "주문 전체 수량을 확보할 수 없습니다.";
    /** 주문 금액 상한(orders.total_amount decimal(12,0)). */
    static final BigDecimal MAX_TOTAL = new BigDecimal("999999999999");

    private SelectionRules() {
    }

    /** 같은 (옵션, 보증)을 두 번 고르면 어느 수량인지 모른다 — 400. */
    static void requireDistinctSlots(List<CartSelection> selections) {
        Set<CartSlot> seen = new HashSet<>();
        for (CartSelection selection : selections) {
            if (!seen.add(selection.slot())) {
                throw ValidationFailures.of("items", "같은 옵션 · 보증 줄을 두 번 고를 수 없습니다.");
            }
        }
    }

    /**
     * 살 수 있는가 · 가격이 그대로인가를 본 뒤 옵션마다 한 줄로 합친 초안. 금액은 catalog 의 지금 값으로 계산한다.
     *
     * @param idempotencyKey 바로 구매 주문이면 요청의 멱등 키, 장바구니 주문이면 null
     * @throws BusinessException 409 STATE_CONFLICT(살 수 없는 줄, details.items 에 줄마다 이유) · PRICE_CHANGED(details.items 에 지금 값),
     *                           400(금액 칸을 넘음)
     */
    static OrderDraft draft(UUID customerId, OrderSource source, UUID idempotencyKey, List<CartSelection> selections,
                            Map<UUID, CartOption> options, PlaceOrderCommand.Address shipTo) {
        requirePurchasable(selections, options);
        requireSamePrices(selections, options);
        PlaceOrderCommand command = toCommand(customerId, source, idempotencyKey, selections, options, shipTo);
        requireWithinTotalLimit(command);
        return command.toDraft();
    }

    /** 옵션 id → 확보할 수량. */
    static Map<UUID, Integer> quantities(List<OrderLine> lines) {
        return lines.stream().collect(Collectors.toMap(OrderLine::optionId, line -> line.quantity().value()));
    }

    /** 살 수 있는가는 장바구니 조회와 같은 판정이다. 재고는 여기서 보지 않는다 — 확보가 조건부 UPDATE 로 가른다. */
    private static void requirePurchasable(List<CartSelection> selections, Map<UUID, CartOption> options) {
        List<Map<String, Object>> unavailable = new ArrayList<>();
        for (CartSelection selection : selections) {
            Unavailability reason = Unavailability.of(options.get(selection.optionId()), selection.warranty(), selection.quantity(),
                    Integer.MAX_VALUE);
            if (reason != null) {
                unavailable.add(Map.of("variantId", selection.optionId(), "warranty", selection.warranty(), "reason", reason.name()));
            }
        }
        if (!unavailable.isEmpty()) {
            throw new BusinessException(OrderErrorCode.STATE_CONFLICT, Map.of("items", unavailable));
        }
    }

    /** 화면에서 본 값과 지금 값이 다르면 주문하지 않고 지금 값을 알린다. 비교는 값으로(scale 무시). */
    private static void requireSamePrices(List<CartSelection> selections, Map<UUID, CartOption> options) {
        List<Map<String, Object>> changed = new ArrayList<>();
        for (CartSelection selection : selections) {
            CartOption option = options.get(selection.optionId());
            boolean unitChanged = selection.expectedUnitPrice().compareTo(option.price()) != 0;
            boolean warrantyChanged = selection.warranty()
                    && (selection.expectedWarrantyPrice() == null || selection.expectedWarrantyPrice().compareTo(option.warrantySurcharge()) != 0);
            if (unitChanged || warrantyChanged) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("variantId", selection.optionId());
                item.put("warranty", selection.warranty());
                item.put("unitPrice", option.price());
                item.put("warrantyPrice", selection.warranty() ? option.warrantySurcharge() : BigDecimal.ZERO);
                changed.add(item);
            }
        }
        if (!changed.isEmpty()) {
            throw new BusinessException(OrderErrorCode.PRICE_CHANGED, Map.of("items", changed));
        }
    }

    /** 금액 칸(12자리)을 넘는 주문은 만들 수 없다 — 400(그대로 두면 Money 가 IllegalArgumentException 으로 500). */
    private static void requireWithinTotalLimit(PlaceOrderCommand command) {
        BigDecimal total = BigDecimal.ZERO;
        for (PlaceOrderCommand.Line line : command.lines()) {
            total = total.add(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())))
                    .add(line.warrantyUnitPrice().multiply(BigDecimal.valueOf(line.warrantyQuantity())));
        }
        if (total.compareTo(MAX_TOTAL) > 0) {
            throw ValidationFailures.of("items", "한 번에 주문할 수 있는 금액을 넘었습니다.");
        }
    }

    /** 옵션마다 한 줄 — 보증 포함 · 미포함 줄을 합친다(수량 = 합, 보증 수량 = 보증 줄의 수량). 이름 · 모델명 · 단가 · 보증가는 catalog 의 지금 값이다. */
    private static PlaceOrderCommand toCommand(UUID customerId, OrderSource source, UUID idempotencyKey, List<CartSelection> selections,
                                               Map<UUID, CartOption> options, PlaceOrderCommand.Address shipTo) {
        Map<UUID, int[]> counts = new LinkedHashMap<>();   // [수량, 보증 수량]
        for (CartSelection selection : selections) {
            int[] count = counts.computeIfAbsent(selection.optionId(), id -> new int[2]);
            count[0] += selection.quantity();
            if (selection.warranty()) {
                count[1] += selection.quantity();
            }
        }
        List<PlaceOrderCommand.Line> lines = new ArrayList<>();
        counts.forEach((optionId, count) -> {
            CartOption option = options.get(optionId);
            lines.add(new PlaceOrderCommand.Line(option.productId(), optionId, count[0], option.price(), count[1],
                    count[1] == 0 ? BigDecimal.ZERO : option.warrantySurcharge(), option.productTitle(), option.modelName(), option.optionTitle()));
        });
        return new PlaceOrderCommand(customerId, source, null, null, idempotencyKey, shipTo, lines);
    }
}
