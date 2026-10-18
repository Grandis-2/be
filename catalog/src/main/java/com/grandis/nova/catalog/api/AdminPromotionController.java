package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.promotion.AdminPromotionView;
import com.grandis.nova.catalog.promotion.LinkableProduct;
import com.grandis.nova.catalog.promotion.PromotionRequest;
import com.grandis.nova.catalog.promotion.PromotionService;
import com.grandis.nova.catalog.web.StrictBodies;
import com.grandis.nova.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 관리자 사전예약 홍보 — 만들기 · 고치기 · 목록 · 상세 · 묶을 수 있는 상품. 삭제는 없다(화면에 없다). */
@Tag(name = "관리자 홍보")
@RestController
@RequestMapping("/api/v1/admin/promotions")
public class AdminPromotionController {

    private final PromotionService promotions;
    private final StrictBodies bodies;

    public AdminPromotionController(PromotionService promotions, StrictBodies bodies) {
        this.promotions = promotions;
        this.bodies = bodies;
    }

    @Operation(summary = "홍보 만들기", description = "201 새로 · 200 같은 Idempotency-Key 의 처음 홍보. 새로 묶는 상품은 회차가 취소되지 않은 사전예약만(400), 다른 홍보에 묶인 상품이면 409(details.productIds). 사진은 업로드 주소만")
    @PostMapping
    public ResponseEntity<ApiResponse<AdminPromotionView>> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = PromotionRequest.class))) @RequestBody String body) {
        PromotionService.Created created = promotions.create(idempotencyKey, bodies.parse(body, PromotionRequest.class));
        return ResponseEntity.status(created.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(ApiResponse.ok(created.promotion()));
    }

    @Operation(summary = "홍보 고치기", description = "모든 칸을 보낸다. 이미 묶인 상품은 일반으로 바꿨어도 남겨 둘 수 있다")
    @PutMapping("/{promotionId}")
    public ApiResponse<AdminPromotionView> replace(@PathVariable UUID promotionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = PromotionRequest.class))) @RequestBody String body) {
        return ApiResponse.ok(promotions.replace(promotionId, bodies.parse(body, PromotionRequest.class)));
    }

    @Operation(summary = "홍보 목록", description = "최신순. status 는 오늘(한국 날짜)과 홍보 기간으로 가른다. 쪽은 앞 10,000개 안에서 시작해야 한다(page × size < 10,000)")
    @GetMapping
    public ApiResponse<ProductPageResponse<AdminPromotionView>> list(@RequestParam(defaultValue = "0") int page,
                                                                     @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        return ApiResponse.ok(ProductPageResponse.from(promotions.list(PageSizes.requirePage(page, PageSizes.require(size)), size)));
    }

    @Operation(summary = "홍보 상세")
    @GetMapping("/{promotionId}")
    public ApiResponse<AdminPromotionView> get(@PathVariable UUID promotionId) {
        return ApiResponse.ok(promotions.get(promotionId));
    }

    @Operation(summary = "묶을 수 있는 상품", description = "회차가 취소되지 않은 사전예약 중 다른 홍보에 묶이지 않은 것. promotionId 를 주면(수정 화면) 그 홍보에 묶인 상품도 싣는다. 쪽은 앞 10,000개 안에서 시작해야 한다(page × size < 10,000)")
    @GetMapping("/linkable-products")
    public ApiResponse<ProductPageResponse<LinkableProduct>> linkable(@RequestParam(required = false) UUID promotionId,
                                                                      @RequestParam(defaultValue = "0") int page,
                                                                      @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        return ApiResponse.ok(ProductPageResponse.from(promotions.linkable(promotionId, PageSizes.requirePage(page, PageSizes.require(size)), size)));
    }
}
