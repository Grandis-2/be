package com.grandis.nova.order.stock.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.stock.admin.AdminStockService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 재고. 권한은 보안 설정이 경로로 막는다(ADMIN). 재고의 정본이다 — catalog 는 이 표를 읽기만 한다.
 * 요청 하나의 옵션은 한 트랜잭션에서 모두 되거나 모두 안 된다.
 */
@RestController
@RequestMapping("/api/v1/admin/products/{productId}/stock")
public class AdminStockController {

    private final AdminStockService service;

    public AdminStockController(AdminStockService service) {
        this.service = service;
    }

    /** 총량 설정. 행이 없는 옵션은 만들고(확보 · 판매 0), 목록에 없는 옵션은 건드리지 않는다. */
    @PutMapping
    public ApiResponse<StockResponse> set(@PathVariable Long productId, @Valid @RequestBody StockRequest request) {
        return ApiResponse.ok(StockResponse.from(service.set(productId, request.toSettings())));
    }
}
