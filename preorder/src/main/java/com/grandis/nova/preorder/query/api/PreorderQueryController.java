package com.grandis.nova.preorder.query.api;

import com.grandis.nova.common.CursorPage;
import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.query.application.PreorderQueryService;
import com.grandis.nova.preorder.web.CurrentViewer;
import com.grandis.nova.preorder.web.Items;
import com.grandis.nova.preorder.web.PageSizes;
import com.grandis.nova.preorder.web.Viewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 내 예약 조회. 목록은 본인 것만, 상세 · 이력은 본인과 관리자만 본다. */
@Tag(name = "사전예약")
@RestController
@RequestMapping("/api/v1/preorders")
class PreorderQueryController {

    private final PreorderQueryService queryService;

    PreorderQueryController(PreorderQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(summary = "내 예약 목록(커서 페이지)")
    @GetMapping
    public ApiResponse<CursorPage<PreorderSummaryResponse>> list(
            @CurrentCustomerId UUID customerId,
            @RequestParam(required = false) PreorderStatus status,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        return ApiResponse.ok(queryService.findMine(customerId, status, productId, cursor, PageSizes.require(size))
                .map(PreorderSummaryResponse::from));
    }

    @Operation(summary = "내 진행 중인 예약(상품마다 많아야 한 건)",
            description = "상품 목록에서 예약 여부 · 단계를 보이는 데 쓴다. 취소가 끝난 예약은 나오지 않는다. "
                    + "보통 한 번에 다 오고, nextCursor 가 있으면 그 값으로 이어 읽는다.")
    @GetMapping("/active")
    public ApiResponse<CursorPage<ActivePreorderResponse>> active(@CurrentCustomerId UUID customerId,
                                                                  @RequestParam(required = false) String cursor) {
        return ApiResponse.ok(queryService.findMyActive(customerId, cursor).map(ActivePreorderResponse::from));
    }

    @Operation(summary = "예약 상세(본인 · 관리자)")
    @GetMapping("/{preorderId}")
    public ApiResponse<PreorderDetailResponse> get(@CurrentViewer Viewer viewer, @PathVariable String preorderId) {
        return ApiResponse.ok(PreorderDetailResponse.from(queryService.findOne(viewer, preorderId)));
    }

    @Operation(summary = "예약 이력(본인 · 관리자)")
    @GetMapping("/{preorderId}/history")
    public ApiResponse<Items<PreorderEventResponse>> history(@CurrentViewer Viewer viewer,
                                                             @PathVariable String preorderId) {
        List<PreorderEventResponse> items = queryService.findHistory(viewer, preorderId).stream()
                .map(PreorderEventResponse::from)
                .toList();
        return ApiResponse.ok(new Items<>(items));
    }
}
