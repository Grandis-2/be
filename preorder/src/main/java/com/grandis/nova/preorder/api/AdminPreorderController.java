package com.grandis.nova.preorder.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.accept.PreorderAcceptService;
import com.grandis.nova.preorder.web.IdempotencyKeys;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 사전예약. 권한은 보안 설정이 경로로 막는다(ADMIN). */
@Tag(name = "관리자 · 예약")
@RestController
@RequestMapping("/api/v1/admin/preorders")
public class AdminPreorderController {

    private final PreorderAcceptService acceptService;

    public AdminPreorderController(PreorderAcceptService acceptService) {
        this.acceptService = acceptService;
    }

    /** 지정 회원의 예약을 대신 접수한다. 사용자 접수와 같은 트랜잭션이고 입장권만 없다. */
    @Operation(summary = "회원 대신 접수(입장권 없음, 사유 필수)")
    @PostMapping
    public ResponseEntity<ApiResponse<PreorderAcceptedResponse>> accept(
            @RequestHeader(IdempotencyKeys.HEADER) String idempotencyKey,
            @Valid @RequestBody AdminPreorderRequest request) {
        return AcceptResponses.accepted(acceptService.acceptByAdmin(request.customerId(), request.productId(),
                request.optionId(), IdempotencyKeys.require(idempotencyKey), request.reason(), request.internalNote()));
    }
}
