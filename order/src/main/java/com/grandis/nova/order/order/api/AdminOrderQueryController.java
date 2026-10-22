package com.grandis.nova.order.order.api;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.repository.AdminOrderFilter;
import com.grandis.nova.order.order.domain.repository.OrderIdPrefix;
import com.grandis.nova.order.order.query.OrderQueryService;
import com.grandis.nova.order.order.query.OrderStatusCounts;
import com.grandis.nova.order.web.PageSizes;
import com.grandis.nova.order.web.ValidationFailures;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/** 관리자 주문 조회. 권한은 보안 설정이 경로로 막는다(ADMIN). */
@RestController
@RequestMapping("/api/v1/admin/orders")
@Tag(name = "관리자 주문")
public class AdminOrderQueryController {

    /** orders.ship_to_name 의 길이. 더 긴 이름은 없다. */
    static final int SHIP_TO_NAME_MAX = 50;
    private static final Pattern PHONE_LAST4 = Pattern.compile("[0-9]{4}");

    private final OrderQueryService queryService;
    private final OrderStatusCounts statusCounts;

    public AdminOrderQueryController(OrderQueryService queryService, OrderStatusCounts statusCounts) {
        this.queryService = queryService;
        this.statusCounts = statusCounts;
    }

    @GetMapping
    @Operation(summary = "주문 목록", description = "최신순. page 는 0 부터, size 1~100. 조건은 모두 선택이고 준 것끼리 AND — "
            + "status · source 정확히 일치, q 주문 번호 앞부분(하이픈 빼고 16진수 8~32자리, 전체 id 도 됨 — 약 65초 안에 만든 주문은 앞 8자리가 같다), "
            + "customerId 회원 · preorderId 예약 공개 UUID 정확히 일치, shipToName 받는 분 이름 일치(앞뒤 공백은 떼고, 대소문자 · 악센트는 가리지 않음, 공백뿐이면 조건 없음, "
            + "50자 이하), shipToPhoneLast4 받는 분 전화번호의 숫자 뒤 4자리, productId · optionId 그 상품 · 옵션이 든 주문(둘 다 주면 같은 주문상품, "
            + "상품이 여럿인 주문도 한 줄), from 이상 · to 미만 주문 시각(ISO-8601, 오프셋 필수 — + 는 %2B 로, 1000-01-01 ~ 9999-12-31). "
            + "형식이 틀린 값 · 모르는 상태 · from 이 to 보다 앞서지 않으면 400. 줄의 preorderId 는 사전예약 주문만 있고 나머지는 null")
    public ApiResponse<OffsetPage<AdminOrderSummaryResponse>> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) OrderSource source,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String shipToName,
            @RequestParam(required = false) String shipToPhoneLast4,
            @RequestParam(required = false) UUID preorderId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID optionId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        int checkedSize = PageSizes.require(size);
        Instant createdFrom = CreatedAtBounds.of("from", from);
        Instant createdTo = CreatedAtBounds.of("to", to);
        if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo)) {
            throw ValidationFailures.of("from", "to 보다 앞서야 합니다.");
        }
        AdminOrderFilter filter = new AdminOrderFilter(status, source, orderIdPrefix(q), customerId, shipToName(shipToName),
                phoneLast4(shipToPhoneLast4), preorderId == null ? null : preorderId.toString(), productId, optionId, createdFrom, createdTo);
        return ApiResponse.ok(queryService
                .findForAdmin(filter, PageSizes.requirePage(page, checkedSize), checkedSize)
                .map(AdminOrderSummaryResponse::from));
    }

    @GetMapping("/stats")
    @Operation(summary = "상태별 주문 수", description = "byStatus 에 모든 상태를 상태 순서로, 주문이 없는 상태는 0. productId 를 주면 그 상품이 든 "
            + "주문만 센다(목록의 다른 조건은 받지 않는다). 전체 수는 최대 5초 늦게 바뀌고, countedAt 은 센 시각이다")
    public ApiResponse<AdminOrderStatusCountsResponse> stats(@RequestParam(required = false) UUID productId) {
        return ApiResponse.ok(AdminOrderStatusCountsResponse.of(
                productId == null ? statusCounts.all() : statusCounts.ofProduct(productId)));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "주문 상세", description = "주문 번호가 정확히 일치할 때만. 없으면 404")
    public ApiResponse<AdminOrderDetailResponse> get(@PathVariable String orderId) {
        return ApiResponse.ok(AdminOrderDetailResponse.from(queryService.findOneForAdmin(orderId)));
    }

    private static OrderIdPrefix orderIdPrefix(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return OrderIdPrefix.parse(q.strip()).orElseThrow(() -> ValidationFailures.of("q",
                "하이픈을 뺀 16진수 %d~32자리여야 합니다.".formatted(OrderIdPrefix.MIN_LENGTH)));
    }

    /** 앞뒤 공백을 떼고, 비면 조건 없음. */
    private static String shipToName(String shipToName) {
        if (shipToName == null || shipToName.isBlank()) {
            return null;
        }
        String trimmed = shipToName.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > SHIP_TO_NAME_MAX) {
            throw ValidationFailures.of("shipToName", "%d자 이하여야 합니다.".formatted(SHIP_TO_NAME_MAX));
        }
        return trimmed;
    }

    private static String phoneLast4(String last4) {
        if (last4 == null || last4.isBlank()) {
            return null;
        }
        if (!PHONE_LAST4.matcher(last4).matches()) {
            throw ValidationFailures.of("shipToPhoneLast4", "숫자 4자리여야 합니다.");
        }
        return last4;
    }
}
