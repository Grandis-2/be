package com.grandis.nova.order.support;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.client.preorder.PreorderSnapshot;
import com.grandis.nova.order.support.OrderFixtures.PreorderProduct;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;

import static org.mockito.BDDMockito.given;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * preorder 내부 API 응답 대역(preorder CatalogStubs 방식). 계약이 아직 없어 가정한 모양이다(NV-55 계획서 §4-1).
 * 이름 · 단가는 {@link OrderFixtures} 의 예약 스냅샷과 같다.
 */
public final class PreorderStubs {

    private PreorderStubs() {
    }

    /**
     * @param preorderId 픽스처로 넣은 preorders.id — 주문의 복합 FK(preorder_id, customer_id)가 실제 행을 가리켜야 한다
     */
    public static PreorderSnapshot snapshot(Long preorderId, String token, Long customerId, PreorderProduct product,
                                            String status, Instant payableFrom) {
        return new PreorderSnapshot(preorderId, token, customerId, product.productId(), product.optionId(),
                OrderFixtures.PRODUCT_TITLE, OrderFixtures.OPTION_TITLE, OrderFixtures.UNIT_PRICE, status, payableFrom);
    }

    public static void stub(PreorderClient client, PreorderSnapshot snapshot) {
        given(client.getPreorder(snapshot.preorderId())).willReturn(ApiResponse.ok(snapshot));
    }

    public static void stubNotFound(PreorderClient client, String token) {
        given(client.getPreorder(token))
                .willThrow(HttpClientErrorException.create(NOT_FOUND, "Not Found", null, null, null));
    }

    /** 읽기 타임아웃. RestClient 는 I/O 실패를 ResourceAccessException 으로 올린다. */
    public static void stubTimeout(PreorderClient client, String token) {
        given(client.getPreorder(token)).willThrow(new ResourceAccessException("Read timed out"));
    }
}
