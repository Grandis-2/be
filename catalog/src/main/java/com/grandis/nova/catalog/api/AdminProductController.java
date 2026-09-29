package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.detail.ProductDetailService;
import com.grandis.nova.catalog.detail.ProductDetailView;
import com.grandis.nova.catalog.edit.OptionValueAddRequest;
import com.grandis.nova.catalog.edit.OptionValueEditRequest;
import com.grandis.nova.catalog.edit.ProductEditRequest;
import com.grandis.nova.catalog.edit.ProductEditService;
import com.grandis.nova.catalog.edit.VariantAddRequest;
import com.grandis.nova.catalog.edit.VariantEditRequest;
import com.grandis.nova.catalog.web.StrictBodies;
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
import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.web.ApiResponse;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 상품 등록 · 목록 · 상세 · 수정(표시 정보 · 가격 · 보증 · 옵션 값 · 옵션). 권한은 보안 설정이 경로로 막는다(ADMIN).
 * 수정 규칙(사전예약 오픈 뒤 금지 · 재계산 · 구성 불변)은 {@link ProductEditService}.
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
    private final ProductEditService editService;
    private final RegistrationRequestParser parser;
    private final StrictBodies bodies;

    public AdminProductController(ProductRegistrationService registrationService, ProductListingService listingService,
                                  ProductDetailService detailService, ProductEditService editService,
                                  RegistrationRequestParser parser, StrictBodies bodies) {
        this.registrationService = registrationService;
        this.listingService = listingService;
        this.detailService = detailService;
        this.editService = editService;
        this.bodies = bodies;
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

    /** 표시 정보 · 기본 가격 · 보증 수정. 보낸 칸만 바꾸고, 사전예약 오픈 뒤면 409. 기본 가격이 바뀌면 수동 가격이 아닌 옵션을 재계산한다. */
    @PatchMapping("/{productId}")
    public ApiResponse<AdminProductResponse> edit(@PathVariable Long productId, @RequestBody String body) {
        return ApiResponse.ok(AdminProductResponse.from(serialized(() -> editService.editProduct(productId, bodies.parse(body, ProductEditRequest.class)))));
    }

    /** 축에 값 추가(새 색상 · 용량). 옵션은 만들지 않는다 — 조합은 아래 variants 로. */
    @PostMapping("/{productId}/option-values")
    public ResponseEntity<ApiResponse<AdminProductResponse>> addOptionValue(@PathVariable Long productId, @RequestBody String body) {
        AdminProductResponse response = AdminProductResponse.from(
                serialized(() -> editService.addOptionValue(productId, bodies.parse(body, OptionValueAddRequest.class))));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /** 값의 표시 문구(정규화값 불변) · 추가금 수정. 추가금이 바뀌면 그 값을 고른 옵션을 재계산한다. */
    @PatchMapping("/{productId}/option-values/{valueId}")
    public ApiResponse<AdminProductResponse> editOptionValue(@PathVariable Long productId, @PathVariable Long valueId,
                                                             @RequestBody String body) {
        return ApiResponse.ok(AdminProductResponse.from(
                serialized(() -> editService.editOptionValue(productId, valueId, bodies.parse(body, OptionValueEditRequest.class)))));
    }

    /** 아직 없는 조합을 옵션으로. 바로 판매 중(ACTIVE). 일반 상품의 재고는 order 의 재고 API 로 따로 넣는다. */
    @PostMapping("/{productId}/variants")
    public ResponseEntity<ApiResponse<ProductDetailView.Variant>> addVariant(@PathVariable Long productId, @RequestBody String body) {
        ProductDetailView.Variant variant = serialized(() -> editService.addVariant(productId, bodies.parse(body, VariantAddRequest.class)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(variant));
    }

    /** 옵션의 수동 가격 · 판매 상태. 가격은 사전예약 오픈 뒤 409, 상태는 언제나. */
    @PatchMapping("/{productId}/variants/{variantId}")
    public ApiResponse<ProductDetailView.Variant> editVariant(@PathVariable Long productId, @PathVariable Long variantId,
                                                             @RequestBody String body) {
        return ApiResponse.ok(serialized(() -> editService.editVariant(productId, variantId, bodies.parse(body, VariantEditRequest.class))));
    }

    /** 키로 상태만 묻는다. 응답이 유실된 클라이언트가 productId 와 진행 상태를 되찾는 데 쓴다. */
    @GetMapping("/registrations/{idempotencyKey}")
    public ApiResponse<RegistrationStatusView> registration(@PathVariable String idempotencyKey) {
        return ApiResponse.ok(registrationService.status(idempotencyKey));
    }

    /**
     * 수정 트랜잭션이 DB 교착의 희생자가 되면 409 STATE_CONFLICT + `details.retryable=true`. 수정은 옵션 행을 id 순으로 잠그는데, 다른 모듈의
     * 외래키 확인(장바구니 · 주문 · 예약 행 INSERT 가 옵션 행에 거는 공유 잠금)은 순서를 catalog 가 정하지 못한다(실측: 역순 INSERT 와 교착 →
     * 500). 수정은 통째로 되돌려졌으니 같은 요청을 다시 보내면 된다. 한 상품의 수정끼리는 상품 행 잠금으로 줄 서므로 교착하지 않는다.
     */
    private static <T> T serialized(Supplier<T> edit) {
        try {
            return edit.get();
        } catch (PessimisticLockingFailureException e) {
            throw new BusinessException(CatalogErrorCode.STATE_CONFLICT, "다른 작업과 겹쳐 처리하지 못했습니다. 다시 시도해 주세요.",
                    Map.of("retryable", true));
        }
    }
}
