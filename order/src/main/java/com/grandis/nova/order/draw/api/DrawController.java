package com.grandis.nova.order.draw.api;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.draw.DrawEntryPaymentService;
import com.grandis.nova.order.draw.DrawEntryService;
import com.grandis.nova.order.draw.DrawQueryService;
import com.grandis.nova.order.order.api.ConfirmPaymentRequest;
import com.grandis.nova.order.web.PageSizes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * 럭키 드로우(회원). 회차 목록 · 상세는 로그인 없이 본다. 응모 · 내 응모 · 응모비 결제는 회원만(관리자는 회원 id 가 없다).
 * 액세스 토큰(Authorization: Bearer)은 payment 에 그대로 전달한다.
 */
@RestController
@RequestMapping(DrawController.BASE_PATH)
@Tag(name = "럭키 드로우")
public class DrawController {

    static final String BASE_PATH = "/api/v1/draws";

    private final DrawQueryService queries;
    private final DrawEntryService entries;
    private final DrawEntryPaymentService payments;

    public DrawController(DrawQueryService queries, DrawEntryService entries, DrawEntryPaymentService payments) {
        this.queries = queries;
        this.entries = entries;
        this.payments = payments;
    }

    @GetMapping
    @Operation(summary = "회차 목록", description = "최신순. page 는 0 부터, size 1~100. phase: SCHEDULED · OPEN · CLOSED")
    public ApiResponse<OffsetPage<DrawResponse>> list(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        int checkedSize = PageSizes.require(size);
        Instant now = queries.now();
        return ApiResponse.ok(queries.list(PageSizes.requirePage(page, checkedSize), checkedSize).map(c -> DrawResponse.of(c, now)));
    }

    @GetMapping("/{drawId}")
    @Operation(summary = "회차 상세")
    public ApiResponse<DrawResponse> get(@PathVariable UUID drawId) {
        return ApiResponse.ok(DrawResponse.of(queries.get(drawId), queries.now()));
    }

    @PostMapping("/{drawId}/entries")
    @Operation(summary = "응모하기", description = "201 새 응모(결제 대기) · 200 이미 응모함(그 응모, 배송지는 바꾸지 않음). 새 응모는 응모 기간에만 — 아니면 409 DRAW_NOT_OPEN")
    public ResponseEntity<ApiResponse<DrawEntryResponse>> enter(@CurrentCustomerId UUID customerId, @PathVariable UUID drawId,
                                                                @Valid @RequestBody EnterDrawRequest request) {
        DrawEntryService.Entered entered = entries.enter(customerId, drawId, request.toShipTo());
        ApiResponse<DrawEntryResponse> body = ApiResponse.ok(DrawEntryResponse.of(entered.entry()));
        if (!entered.created()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + drawId + "/entries/me")).body(body);
    }

    @GetMapping("/{drawId}/entries/me")
    @Operation(summary = "내 응모", description = "응모하지 않았으면 404 DRAW_ENTRY_NOT_FOUND")
    public ApiResponse<DrawEntryResponse> mine(@CurrentCustomerId UUID customerId, @PathVariable UUID drawId) {
        return ApiResponse.ok(DrawEntryResponse.of(entries.mine(customerId, drawId)));
    }

    @PostMapping("/{drawId}/entries/me/payment-attempts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "응모비 결제 준비", description = "결제창을 열 값. 금액은 응모비다. 결제 대기 · 응모 기간일 때만")
    public ApiResponse<EntryPaymentAttemptResponse> prepare(@CurrentCustomerId UUID customerId, @PathVariable UUID drawId,
                                                            HttpServletRequest httpRequest) {
        return ApiResponse.ok(EntryPaymentAttemptResponse.of(payments.prepare(customerId, BearerTokens.extract(httpRequest).orElse(null), drawId)));
    }

    @PostMapping("/{drawId}/entries/me/payment-attempts/{tossOrderId}/confirm")
    @Operation(summary = "응모비 결제 승인", description = "200 이고 result 로 가른다(APPROVED · DECLINED · PENDING). 금액은 응모비와 대조만 한다")
    public ApiResponse<EntryConfirmResponse> confirm(@CurrentCustomerId UUID customerId, @PathVariable UUID drawId,
                                                     @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{6,64}") String tossOrderId,
                                                     @Valid @RequestBody ConfirmPaymentRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.ok(EntryConfirmResponse.of(payments.confirm(customerId, BearerTokens.extract(httpRequest).orElse(null), drawId,
                tossOrderId, request.paymentKey(), request.amount())));
    }
}
