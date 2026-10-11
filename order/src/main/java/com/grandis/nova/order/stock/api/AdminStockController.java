package com.grandis.nova.order.stock.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.stock.admin.AdminStockService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 관리자 재고. 권한은 보안 설정이 경로로 막는다(ADMIN). 재고의 정본이다 — catalog 는 이 표를 읽기만 한다.
 * 요청 하나의 옵션은 한 트랜잭션에서 모두 되거나 모두 안 된다.
 *
 * 설정(PUT)과 초기화(POST)를 메서드로 가른다. 쿼리 파라미터 · 헤더로 가르면 빠지거나 철자가 틀려도 조용히 무시돼
 * 덮어쓰기가 되는데, 그게 초기화가 막으려는 사고다. 메서드가 틀리면 405 로 드러난다.
 */
@RestController
@RequestMapping("/api/v1/admin/products/{productId}/stock")
public class AdminStockController {

    private final AdminStockService service;

    public AdminStockController(AdminStockService service) {
        this.service = service;
    }

    /** 그 상품 옵션 전부의 재고. 재고를 넣지 않은 옵션은 registered=false, 사전예약 상품은 tracked=false 와 빈 목록. */
    @GetMapping
    public ApiResponse<StockOverviewResponse> find(@PathVariable UUID productId) {
        return ApiResponse.ok(StockOverviewResponse.from(service.find(productId)));
    }

    /** 총량 설정. 행이 없는 옵션은 만들고(확보 · 판매 0), 목록에 없는 옵션은 건드리지 않는다. */
    @PutMapping
    public ApiResponse<StockResponse> set(@PathVariable UUID productId, @Valid @RequestBody StockRequest request) {
        return ApiResponse.ok(StockResponse.from(service.set(productId, request.toSettings())));
    }

    /**
     * 초기화. 행이 없는 옵션만 만들고 있는 옵션은 그대로 둔다(created=false 와 현재 값). 상품 등록의 재고 단계가 부른다.
     * 여러 행이라 가리킬 자원이 하나가 아니어서 201 · Location 없이 늘 200 이다.
     */
    @PostMapping
    public ApiResponse<StockResponse> initialize(@PathVariable UUID productId,
                                                 @Valid @RequestBody StockRequest request) {
        return ApiResponse.ok(StockResponse.from(service.initialize(productId, request.toSettings())));
    }
}
