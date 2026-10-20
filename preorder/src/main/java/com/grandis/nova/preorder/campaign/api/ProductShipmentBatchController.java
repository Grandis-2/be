package com.grandis.nova.preorder.campaign.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.campaign.ShipmentBatchResponse;
import com.grandis.nova.preorder.campaign.application.ShipmentBatchQueryService;
import com.grandis.nova.preorder.web.Items;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 배송 차수 공개 조회. "몇 번째 순번까지 1차 배송" 안내에 쓴다.
 * 로그인 없이 볼 수 있고, 오픈 뒤에는 바뀌지 않으므로 버전이 없다. 오픈 전 교체는 드물어 브라우저 · CDN 이 잠깐 들고 있게 한다.
 */
@Tag(name = "배송 차수")
@RestController
@RequestMapping("/api/v1/preorders/products/{productId}/shipment-batches")
class ProductShipmentBatchController {

    /** 브라우저 · CDN 이 들고 있는 시간. 오픈 전 차수 교체가 이만큼 늦게 보일 수 있다 — 접수 때 차수는 회차 행으로 다시 정한다. */
    static final Duration MAX_AGE = Duration.ofSeconds(60);

    private final ShipmentBatchQueryService queryService;

    ProductShipmentBatchController(ShipmentBatchQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(summary = "배송 차수 공개 조회(로그인 없이)")
    @SecurityRequirements
    @GetMapping
    public ResponseEntity<ApiResponse<Items<ShipmentBatchResponse>>> list(@PathVariable UUID productId) {
        List<ShipmentBatchResponse> items = queryService.findPublished(productId).stream()
                .map(ShipmentBatchResponse::from)
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(MAX_AGE).cachePublic())
                .body(ApiResponse.ok(new Items<>(items)));
    }
}
