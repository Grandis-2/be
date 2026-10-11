package com.grandis.nova.preorder.payability;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.web.CurrentViewer;
import com.grandis.nova.preorder.web.Viewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 서비스끼리만 부르는 API. 호출한 쪽이 사용자 토큰을 그대로 전달한다. */
@Tag(name = "서비스 간 · 결제 가능")
@RestController
@RequestMapping("/internal/preorders")
class InternalPreorderController {

    private final PayabilityService payabilityService;

    InternalPreorderController(PayabilityService payabilityService) {
        this.payabilityService = payabilityService;
    }

    @Operation(summary = "결제 가능 여부 — 공개 번호로. order 가 결제 시작 전에 사용자 토큰을 실어 호출")
    @GetMapping("/{preorderId}/payability")
    public ApiResponse<PayabilityResponse> payability(@CurrentViewer Viewer viewer, @PathVariable String preorderId) {
        return ApiResponse.ok(PayabilityResponse.from(payabilityService.check(viewer, preorderId)));
    }

    /** 공개 번호와 내부 id 가 둘 다 UUID 모양이라 경로(ids)로 입구를 나눈다. 응답 · 오류는 공개 번호 입구와 같다. */
    @Operation(summary = "결제 가능 여부 — 예약 내부 id 로(order 가 주문에 저장한 id)")
    @GetMapping("/ids/{preorderInternalId}/payability")
    public ApiResponse<PayabilityResponse> payabilityById(@CurrentViewer Viewer viewer,
                                                          @PathVariable UUID preorderInternalId) {
        return ApiResponse.ok(PayabilityResponse.from(payabilityService.checkById(viewer, preorderInternalId)));
    }
}
