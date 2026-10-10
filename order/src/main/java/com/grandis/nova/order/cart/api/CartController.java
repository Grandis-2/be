package com.grandis.nova.order.cart.api;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.cart.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 내 장바구니(명세 F-U-07). 회원(USER)만.
 * 조회 · 담기는 상품 정보를 catalog 에 묻는다 — 사용자의 액세스 토큰(Authorization: Bearer)을 그대로 전달한다({@link BearerTokens#extract}).
 */
@Tag(name = "장바구니")
@RestController
@RequestMapping("/api/v1/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @Operation(summary = "내 장바구니", description = "판매 중지 · 재고 부족 줄도 지우지 않고 purchasable=false 와 unavailableReason 으로 알린다")
    @GetMapping
    public ApiResponse<CartResponse> cart(@CurrentCustomerId UUID customerId, HttpServletRequest httpRequest) {
        return ApiResponse.ok(CartResponse.from(cartService.view(customerId, BearerTokens.extract(httpRequest).orElse(null))));
    }

    @Operation(summary = "장바구니 줄 수(헤더 배지)")
    @GetMapping("/count")
    public ApiResponse<CartCountResponse> count(@CurrentCustomerId UUID customerId) {
        return ApiResponse.ok(new CartCountResponse(cartService.count(customerId)));
    }

    @Operation(summary = "장바구니 담기", description = "같은 (옵션, 보증)이면 합산한다. 재고는 확인만 하고 확보하지 않는다. 응답이 불명이면 다시 보내지 말고 GET /cart 로 확인한다(재전송은 또 합산된다)")
    @PostMapping("/items")
    public ApiResponse<CartItemResponse> add(@CurrentCustomerId UUID customerId, @Valid @RequestBody CartItemAddRequest request,
                                             HttpServletRequest httpRequest) {
        return ApiResponse.ok(CartItemResponse.from(cartService.add(customerId, BearerTokens.extract(httpRequest).orElse(null),
                request.variantId(), request.quantity(), request.warrantySelected())));
    }

    @Operation(summary = "장바구니 수량 변경", description = "수량 절대값만. 옵션 · 보증 교체는 삭제 후 담기")
    @PatchMapping("/items/{cartItemId}")
    public ApiResponse<CartItemResponse> changeQuantity(@CurrentCustomerId UUID customerId, @PathVariable UUID cartItemId,
                                                        @Valid @RequestBody CartItemQuantityRequest request) {
        return ApiResponse.ok(CartItemResponse.from(cartService.changeQuantity(customerId, cartItemId, request.quantity())));
    }

    @Operation(summary = "장바구니 줄 삭제")
    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<Void> remove(@CurrentCustomerId UUID customerId, @PathVariable UUID cartItemId) {
        cartService.remove(customerId, cartItemId);
        return ResponseEntity.noContent().build();
    }
}
