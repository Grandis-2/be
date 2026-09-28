package com.grandis.nova.preorder.accept;

import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.web.IdempotencyKeys;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 사전예약. queue-gateway 를 거쳐 들어온다. */
@Tag(name = "사전예약")
@RestController
@RequestMapping("/api/v1/preorders")
class PreorderController {

    static final String ADMISSION_TICKET = "X-Admission-Ticket";

    private final PreorderAcceptService acceptService;

    public PreorderController(PreorderAcceptService acceptService) {
        this.acceptService = acceptService;
    }

    @Operation(summary = "사전예약 접수 — 대기열 입장권 · 접수 키 필요")
    @PostMapping
    public ResponseEntity<ApiResponse<PreorderAcceptedResponse>> accept(
            @CurrentCustomerId Long customerId,
            @RequestParam Long productId,
            @RequestHeader(IdempotencyKeys.HEADER) String idempotencyKey,
            @RequestHeader(value = ADMISSION_TICKET, required = false) String admissionTicket,
            @Valid @RequestBody PreorderRequest request) {
        return AcceptResponses.accepted(acceptService.acceptByCustomer(customerId, productId, request.productId(),
                request.optionId(), IdempotencyKeys.require(idempotencyKey), admissionTicket));
    }
}
