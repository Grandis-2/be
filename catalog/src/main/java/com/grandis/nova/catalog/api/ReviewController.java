package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.review.ReviewCreateRequest;
import com.grandis.nova.catalog.review.ReviewService;
import com.grandis.nova.catalog.review.ReviewUpdateRequest;
import com.grandis.nova.catalog.review.ReviewView;
import com.grandis.nova.catalog.web.StrictBodies;
import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 리뷰. 쓰기(작성 · 수정 · 삭제)와 내 리뷰는 USER, 상품 리뷰 · 모아보기는 공개다(SecurityConfig).
 * 쓰기 본문은 관리자 API 와 같은 엄격한 매퍼로 읽는다 — 모르는 칸 · 같은 키 두 번 · 소수 별점은 그 칸의 400.
 */
@RestController
public class ReviewController {

    private final ReviewService reviews;
    private final StrictBodies bodies;

    public ReviewController(ReviewService reviews, StrictBodies bodies) {
        this.reviews = reviews;
        this.bodies = bodies;
    }

    @PostMapping("/api/v1/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReviewView> write(@CurrentCustomerId Long customerId, @RequestBody String body) {
        return ApiResponse.ok(reviews.write(customerId, bodies.parse(body, ReviewCreateRequest.class)));
    }

    @PatchMapping("/api/v1/reviews/{reviewId}")
    public ApiResponse<ReviewView> revise(@CurrentCustomerId Long customerId, @PathVariable Long reviewId,
                                          @RequestBody String body) {
        return ApiResponse.ok(reviews.revise(customerId, reviewId, bodies.parse(body, ReviewUpdateRequest.class)));
    }

    @DeleteMapping("/api/v1/reviews/{reviewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentCustomerId Long customerId, @PathVariable Long reviewId) {
        reviews.delete(customerId, reviewId);
    }

    /** 상품 상세의 구매 후기 탭. */
    @GetMapping("/api/v1/products/{productId}/reviews")
    public ApiResponse<ProductPageResponse<ReviewView>> productReviews(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        return ApiResponse.ok(ProductPageResponse.from(
                reviews.productReviews(productId, PageSizes.requirePage(page), PageSizes.require(size))));
    }

    /** 구매후기 모아보기. categoryId 는 상위면 하위 포함. */
    @GetMapping("/api/v1/reviews")
    public ApiResponse<ProductPageResponse<ReviewView>> visibleReviews(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        return ApiResponse.ok(ProductPageResponse.from(
                reviews.visibleReviews(categoryId, PageSizes.requirePage(page), PageSizes.require(size))));
    }

    /** 내 리뷰. orderItemId 를 주면 그 주문상품의 리뷰만 — 주문 내역의 "리뷰 쓰기 / 내가 쓴 리뷰 보기" 를 가른다. */
    @GetMapping("/api/v1/reviews/mine")
    public ApiResponse<ProductPageResponse<ReviewView>> myReviews(
            @CurrentCustomerId Long customerId,
            @RequestParam(required = false) Long orderItemId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        return ApiResponse.ok(ProductPageResponse.from(
                reviews.myReviews(customerId, orderItemId, PageSizes.requirePage(page), PageSizes.require(size))));
    }
}
