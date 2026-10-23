package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.cache.CatalogReadCache;
import com.grandis.nova.catalog.review.ReviewCreateRequest;
import com.grandis.nova.catalog.review.ReviewService;
import com.grandis.nova.catalog.review.ReviewUpdateRequest;
import com.grandis.nova.catalog.review.ReviewView;
import com.grandis.nova.catalog.review.WritableItemView;
import com.grandis.nova.catalog.review.WritableReviewQuery;
import com.grandis.nova.catalog.web.StrictBodies;
import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 상품 리뷰. 쓰기(작성 · 수정 · 삭제) · 내 리뷰 · 쓸 수 있는 주문상품은 USER, 상품 리뷰 · 모아보기는 공개다(SecurityConfig).
 * 쓰기 본문은 관리자 API 와 같은 엄격한 매퍼로 읽는다 — 모르는 칸 · 같은 키 두 번 · 소수 별점은 그 칸의 400.
 */
@Tag(name = "상품 리뷰")
@RestController
public class ReviewController {

    private final ReviewService reviews;
    private final WritableReviewQuery writable;
    private final CatalogReadCache cache;
    private final StrictBodies bodies;
    private final Clock clock;

    public ReviewController(ReviewService reviews, WritableReviewQuery writable, CatalogReadCache cache, StrictBodies bodies, Clock clock) {
        this.reviews = reviews;
        this.writable = writable;
        this.cache = cache;
        this.bodies = bodies;
        this.clock = clock;
    }

    @Operation(summary = "리뷰 작성", description = "배송 완료된 내 일반 판매 주문상품(사전예약 · 드로우 당첨 증정 제외)에 하나. body 는 앞뒤 공백을 뺀 1~2,000자")
    @PostMapping("/api/v1/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReviewView> write(@CurrentCustomerId UUID customerId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = ReviewCreateRequest.class))) @RequestBody String body) {
        return ApiResponse.ok(reviews.write(customerId, bodies.parse(body, ReviewCreateRequest.class)));
    }

    @Operation(summary = "내 리뷰 수정", description = "보낸 칸만 바꾼다 — 하나도 없으면 400. body 는 앞뒤 공백을 뺀 1~2,000자")
    @PatchMapping("/api/v1/reviews/{reviewId}")
    public ApiResponse<ReviewView> revise(@CurrentCustomerId UUID customerId, @PathVariable UUID reviewId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = ReviewUpdateRequest.class))) @RequestBody String body) {
        return ApiResponse.ok(reviews.revise(customerId, reviewId, bodies.parse(body, ReviewUpdateRequest.class)));
    }

    @Operation(summary = "내 리뷰 삭제")
    @DeleteMapping("/api/v1/reviews/{reviewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentCustomerId UUID customerId, @PathVariable UUID reviewId) {
        reviews.delete(customerId, reviewId);
    }

    /** 상품 상세의 구매 후기 탭. */
    @Operation(summary = "상품 리뷰(로그인 없이)", description = "최신순. 쪽은 앞 10,000개 안에서 시작해야 한다(page × size < 10,000)")
    @SecurityRequirements
    @GetMapping("/api/v1/products/{productId}/reviews")
    public ResponseEntity<ApiResponse<ProductPageResponse<ReviewView>>> productReviews(
            @PathVariable UUID productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        return PublicCache.ok(cache.productReviews(productId, PageSizes.requirePage(page, PageSizes.require(size)), size).map(ProductPageResponse::from),
                CatalogReadCache.REVIEW_TTL, PublicCache.REVIEW, clock);
    }

    /** 구매후기 모아보기. categoryId 는 상위면 하위 포함. */
    @Operation(summary = "구매후기 모아보기(로그인 없이)", description = "최신순. 쪽은 앞 10,000개 안에서 시작해야 한다(page × size < 10,000)")
    @SecurityRequirements
    @GetMapping("/api/v1/reviews")
    public ResponseEntity<ApiResponse<ProductPageResponse<ReviewView>>> visibleReviews(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        return PublicCache.ok(cache.visibleReviews(categoryId, PageSizes.requirePage(page, PageSizes.require(size)), size).map(ProductPageResponse::from),
                CatalogReadCache.REVIEW_TTL, PublicCache.REVIEW, clock);
    }

    /**
     * 내 리뷰. orderItemId 를 주면(여러 번 · 쉼표, 최대 {@link PageSizes#MAX}개, 같은 id 는 한 번) 그 주문상품들의 리뷰만 — 주문 내역이 화면의
     * 주문상품마다 "리뷰 쓰기 / 리뷰 수정" 을 가른다. 주문상품마다 리뷰는 0건 또는 1건이라, size 를 주지 않으면 보낸 id 수로 두어 한 쪽에 다 온다
     * (기본 20으로 두면 id 를 21개 넘게 보낸 화면이 뒤쪽을 "리뷰 쓰기" 로 잘못 보인다). 값이 통째로 빈 orderItemId 는 보내지 않은 것과 같다.
     */
    @Operation(summary = "내 리뷰", description = "최신순. orderItemId 를 주면(여러 번 · 쉼표, 1~100개, 같은 id 는 한 번) 그 주문상품들의 리뷰만 — "
            + "이때 size 를 주지 않으면 보낸 id 수다(orderItemId 가 없으면 20). 값이 통째로 빈 orderItemId 는 보내지 않은 것과 같다. 빈 원소 · 100개 초과는 400. "
            + "쪽은 앞 10,000개 안에서 시작해야 한다(page × size < 10,000)")
    @GetMapping("/api/v1/reviews/mine")
    public ApiResponse<ProductPageResponse<ReviewView>> myReviews(
            @CurrentCustomerId UUID customerId,
            @RequestParam(required = false) List<String> orderItemId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        Set<UUID> orderItemIds = orderItemIds(orderItemId);
        int pageSize = PageSizes.require(size != null ? size : orderItemIds.isEmpty() ? PageSizes.DEFAULT : orderItemIds.size());
        return ApiResponse.ok(ProductPageResponse.from(
                reviews.myReviews(customerId, orderItemIds, PageSizes.requirePage(page, pageSize), pageSize)));
    }

    /**
     * 여러 번 보낸 값과 쉼표로 이은 값을 섞어도 받는다 — List&lt;UUID&gt; 로 받으면 Spring 은 값이 하나일 때만 쉼표를 나눠, 섞으면 바인딩에서 400 이었다.
     * 빈 원소(orderItemId=a, 처럼) · UUID 가 아닌 값은 400. 보내지 않았거나 값이 통째로 비면 거르지 않는다.
     */
    private static Set<UUID> orderItemIds(List<String> values) {
        if (values == null || values.size() == 1 && values.getFirst().isBlank()) {
            return Set.of();
        }
        List<UUID> requested = new ArrayList<>();
        for (String value : values) {
            for (String part : value.split(",", -1)) {
                if (part.isBlank()) {
                    throw ValidationFailures.of("orderItemId", "빈 주문상품 id 가 있습니다.");
                }
                try {
                    requested.add(UUID.fromString(part.strip()));
                } catch (IllegalArgumentException e) {
                    throw ValidationFailures.of("orderItemId", "주문상품 id 는 UUID 여야 합니다.");
                }
            }
        }
        Set<UUID> distinct = new LinkedHashSet<>(requested);
        if (distinct.size() > PageSizes.MAX) {
            throw ValidationFailures.of("orderItemId", "주문상품 id 는 %d개까지입니다.".formatted(PageSizes.MAX));
        }
        return distinct;
    }

    /** 마이페이지의 "리뷰 쓰기" 탭. total 은 탭 칩의 수다. */
    @Operation(summary = "리뷰를 쓸 수 있는 내 주문상품",
            description = "배송 완료된 장바구니 · 바로 구매 주문상품 중 아직 리뷰를 쓰지 않은 것(사전예약 · 드로우 당첨 증정 제외). 주문 최신순. "
                    + "최근 배송 완료 주문 500건까지 본다. 쪽은 앞 10,000개 안에서 시작해야 한다(page × size < 10,000)")
    @GetMapping("/api/v1/reviews/writable")
    public ApiResponse<ProductPageResponse<WritableItemView>> writableItems(
            @CurrentCustomerId UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        return ApiResponse.ok(ProductPageResponse.from(
                writable.find(customerId, PageSizes.requirePage(page, PageSizes.require(size)), size)));
    }
}
