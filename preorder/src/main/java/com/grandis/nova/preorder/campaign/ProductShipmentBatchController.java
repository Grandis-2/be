package com.grandis.nova.preorder.campaign;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.query.PreorderQueryService;
import com.grandis.nova.preorder.web.Items;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 배송 차수 공개 조회. "몇 번째 순번까지 1차 배송" 안내에 쓴다.
 * 로그인 없이 볼 수 있고, 오픈 뒤에는 바뀌지 않으므로 버전이 없다.
 */
@Tag(name = "배송 차수")
@RestController
@RequestMapping("/api/v1/products/{productId}/shipment-batches")
public class ProductShipmentBatchController {

    private final PreorderQueryService queryService;

    public ProductShipmentBatchController(PreorderQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(summary = "배송 차수 공개 조회(로그인 없이)")
    @SecurityRequirements
    @GetMapping
    public ApiResponse<Items<ShipmentBatchResponse>> list(@PathVariable Long productId) {
        List<ShipmentBatchResponse> items = queryService.findShipmentBatches(productId).stream()
                .map(ShipmentBatchResponse::from)
                .toList();
        return ApiResponse.ok(new Items<>(items));
    }
}
