package com.grandis.nova.preorder.api;

import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.accept.PreorderAcceptService;
import com.grandis.nova.preorder.web.IdempotencyKeys;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 사전예약. queue-gateway 를 거쳐 들어온다. */
@RestController
@RequestMapping("/api/v1/preorders")
public class PreorderController {

    static final String ADMISSION_TICKET = "X-Admission-Ticket";

    private final PreorderAcceptService acceptService;

    public PreorderController(PreorderAcceptService acceptService) {
        this.acceptService = acceptService;
    }

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
