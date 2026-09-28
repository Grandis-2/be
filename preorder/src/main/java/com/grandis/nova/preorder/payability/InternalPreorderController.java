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

/** 서비스끼리만 부르는 API. 호출한 쪽이 사용자 토큰을 그대로 전달한다. */
@Tag(name = "서비스 간 · 결제 가능")
@RestController
@RequestMapping("/internal/preorders")
class InternalPreorderController {

    private final PayabilityService payabilityService;

    InternalPreorderController(PayabilityService payabilityService) {
        this.payabilityService = payabilityService;
    }

    @Operation(summary = "결제 가능 여부 — order 가 결제 시작 전에 사용자 토큰을 실어 호출")
    @GetMapping("/{preorderId}/payability")
    public ApiResponse<PayabilityResponse> payability(@CurrentViewer Viewer viewer, @PathVariable String preorderId) {
        return ApiResponse.ok(PayabilityResponse.from(payabilityService.check(viewer, preorderId)));
    }
}
