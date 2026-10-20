package com.grandis.nova.order.stock.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.stock.admin.StockCorrectionService;
import com.grandis.nova.order.stock.admin.StockReconcileQueryService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 관리자 재고 장부 대조 · 보정. 권한은 보안 설정이 경로로 막는다(ADMIN).
 *
 * 운영 흐름: 마지막 대조 결과로 어긋난 옵션을 보고 → 옵션마다 미리보기(GET)로 지금도 어긋나는지 확인 → 보정(POST)으로 주문 기준에 맞춘다.
 * 보정은 숫자를 받지 않는다 — 주문 기준 수량으로만 덮는다.
 */
@RestController
public class AdminStockReconcileController {

    private final StockCorrectionService correction;
    private final StockReconcileQueryService query;

    public AdminStockReconcileController(StockCorrectionService correction, StockReconcileQueryService query) {
        this.correction = correction;
        this.query = query;
    }

    /** 마지막으로 끝난 대조와 어긋난 옵션. 아직 끝난 대조가 없으면 404. */
    @GetMapping("/api/v1/admin/stock/reconcile-runs/latest")
    public ApiResponse<StockReconcileRunResponse> latest() {
        return ApiResponse.ok(StockReconcileRunResponse.from(query.latest()));
    }

    /** 미리보기 — 지금 장부와 주문 기준 수량. 아무것도 바꾸지 않는다. */
    @GetMapping("/api/v1/admin/products/{productId}/stock/options/{optionId}/reconcile")
    public ApiResponse<StockBalanceResponse> preview(@PathVariable UUID productId, @PathVariable UUID optionId) {
        return ApiResponse.ok(StockBalanceResponse.from(correction.preview(productId, optionId)));
    }

    /** 보정 — 확보 · 판매를 주문 기준 수량으로 덮는다. 이미 같으면 바꾸지 않고 changed=false. 주문 기준이 총량을 넘으면 409. */
    @PostMapping("/api/v1/admin/products/{productId}/stock/options/{optionId}/reconcile")
    public ApiResponse<StockCorrectionResponse> correct(@PathVariable UUID productId, @PathVariable UUID optionId,
                                                        @Valid @RequestBody StockCorrectionRequest request, Authentication admin) {
        return ApiResponse.ok(StockCorrectionResponse.from(
                correction.correct(productId, optionId, request.validReason(), admin.getName())));
    }
}
