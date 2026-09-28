package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.detail.ProductDetailService;
import com.grandis.nova.catalog.listing.AdminProductListFilter;
import com.grandis.nova.catalog.listing.AdminProductListItem;
import com.grandis.nova.catalog.listing.ProductListingService;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;
import com.grandis.nova.catalog.registration.ProductRegistrationRequest;
import com.grandis.nova.catalog.registration.ProductRegistrationService;
import com.grandis.nova.catalog.registration.RegistrationRequestParser;
import com.grandis.nova.catalog.registration.RegistrationOutcome;
import com.grandis.nova.catalog.registration.RegistrationStatusView;
import com.grandis.nova.common.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 상품 등록 · 목록 · 상세. 권한은 보안 설정이 경로로 막는다(ADMIN).
 *
 * 목록 · 상세에는 노출 규칙이 없다 — 비공개 · 미완료 · 판매 중지 · 오래된 마감도 전부 나오고, 공개 여부 · 등록 완료 · 막힘 사유가 같이 실린다.
 * 배송 차수는 싣지 않는다(회원 상세와 같은 이유 — catalog 는 shipment_batches 를 읽지 않는다). 없는 상품만 404.
 *
 * 응답 코드가 결과 갈래를 말한다 — 201 새로 저장(등록 ①, 미리보기 포함), 200 완료된 등록의 재생, 202 미완료 등록이 있음.
 * 200 · 202 는 등록 상태(고정 필드)만 싣는다 — 같은 키의 재전송 응답이 시점마다 달라지지 않게. 미리보기는 상세 API 로 따로 본다.
 * 등록 ②③(preorder · order 호출 · 완료 · 공개 전환)은 등록 조율 티켓에서 이어진다. 그 전까지 202 는 "① 만 끝났다" 는 뜻이고
 * 같은 키로 다시 보내도 진행되지 않는다. 상품은 비공개 · 미완료로 남는다(관리자 미리보기로만 보인다).
 * 본문은 모르는 칸을 거절한다({@link RegistrationRequestParser}).
 */
@RestController
@RequestMapping("/api/v1/admin/products")
public class AdminProductController {

    private final ProductRegistrationService registrationService;
    private final ProductListingService listingService;
    private final ProductDetailService detailService;
    private final RegistrationRequestParser parser;

    public AdminProductController(ProductRegistrationService registrationService, ProductListingService listingService,
                                  ProductDetailService detailService, RegistrationRequestParser parser) {
        this.registrationService = registrationService;
        this.listingService = listingService;
        this.detailService = detailService;
        this.parser = parser;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProductRegistrationResponse>> register(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody String body) {
        ProductRegistrationRequest request = parser.parse(body);
        RegistrationOutcome outcome = registrationService.register(idempotencyKey, request);
        return switch (outcome.kind()) {
            case CREATED -> ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.ok(new ProductRegistrationResponse(outcome.registration(), outcome.preview())));
            case REPLAYED -> ResponseEntity.ok(ApiResponse.ok(new ProductRegistrationResponse(outcome.registration(), null)));
            case IN_PROGRESS -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.ok(new ProductRegistrationResponse(outcome.registration(), null)));
        };
    }

    @GetMapping
    public ApiResponse<ProductPageResponse<AdminProductListItem>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) SaleMode saleMode,
            @RequestParam(required = false) SaleStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        AdminProductListFilter filter = new AdminProductListFilter(q, saleMode, status);
        return ApiResponse.ok(ProductPageResponse.from(
                listingService.listForAdmin(filter, PageSizes.requirePage(page), PageSizes.require(size))));
    }

    @GetMapping("/{productId}")
    public ApiResponse<AdminProductResponse> product(@PathVariable Long productId) {
        return ApiResponse.ok(AdminProductResponse.from(detailService.findAdminProduct(productId)));
    }

    /** 키로 상태만 묻는다. 응답이 유실된 클라이언트가 productId 와 진행 상태를 되찾는 데 쓴다. */
    @GetMapping("/registrations/{idempotencyKey}")
    public ApiResponse<RegistrationStatusView> registration(@PathVariable String idempotencyKey) {
        return ApiResponse.ok(registrationService.status(idempotencyKey));
    }
}
