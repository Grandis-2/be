package com.grandis.nova.preorder.query.api;

import com.grandis.nova.common.CursorPage;
import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.query.application.PreorderDisplayStatus;
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
import java.util.Set;
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

    @Operation(summary = "내 예약 목록(커서 페이지)",
            description = "최신순. displayStatus 는 여러 번 줄 수 있고(그중 하나), 지금 시각으로 가른다 — 응답의 displayStatus 와 같다. "
                    + "status(원장 상태) · productId 와는 AND 다. counts 는 이 조건과 상관없이 내 예약 전부의 화면 단계별 수다. "
                    + "모르는 값은 400")
    @GetMapping
    public ApiResponse<MyPreordersResponse> list(
            @CurrentCustomerId UUID customerId,
            @RequestParam(required = false) PreorderStatus status,
            @RequestParam(required = false) Set<PreorderDisplayStatus> displayStatus,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        CursorPage<PreorderSummaryResponse> page = queryService.findMine(customerId, status,
                        displayStatus == null ? Set.of() : displayStatus, productId, cursor, PageSizes.require(size))
                .map(PreorderSummaryResponse::from);
        return ApiResponse.ok(MyPreordersResponse.of(page, queryService.countMine(customerId)));
    }

    @Operation(summary = "내 예약의 화면 단계별 수",
            description = "목록 없이 숫자만. 머리글 · 마이페이지 배지의 \"구매 확정 필요\"(PAYABLE + PAYMENT_IN_PROGRESS) 같은 수에 쓴다. "
                    + "모든 단계가 들어 있고 없는 단계는 0")
    @GetMapping("/counts")
    public ApiResponse<PreorderCountsResponse> counts(@CurrentCustomerId UUID customerId) {
        return ApiResponse.ok(new PreorderCountsResponse(queryService.countMine(customerId)));
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
