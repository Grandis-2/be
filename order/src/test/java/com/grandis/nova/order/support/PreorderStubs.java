package com.grandis.nova.order.support;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.client.preorder.PreorderPayability;
import com.grandis.nova.order.support.OrderFixtures.PreorderProduct;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * preorder 결제 가능 확인 API 응답 대역(preorder CatalogStubs 방식). 모양은 preorder PayabilityResponse 와 같다
 * (계약 대조는 PreorderClientTest). 이름 · 단가는 {@link OrderFixtures} 의 예약 스냅샷과 같다.
 */
public final class PreorderStubs {

    /** payable 일 때 PAYABLE 이 된 지 얼마나 됐는지. 결제 기한은 preorder 규칙대로 그 24시간 뒤다. */
    static final Duration PAYABLE_FOR = Duration.ofHours(1);
    static final Duration PAYMENT_WINDOW = Duration.ofHours(24);

    private PreorderStubs() {
    }

    /**
     * 결제 가능한 예약.
     *
     * @param preorderInternalId 픽스처로 넣은 preorders.id — 주문의 복합 FK(preorder_id, customer_id)가 실제 행을 가리켜야 한다
     */
    public static PreorderPayability payable(Long preorderInternalId, String preorderId, Long customerId,
                                             PreorderProduct product) {
        return payability(preorderInternalId, preorderId, customerId, product, "PAYABLE", true, null);
    }

    /**
     * 결제할 수 없는 예약. preorder 가 사유를 정한다(order 는 따르기만 한다).
     *
     * @param reason NOT_YET_REGISTERED · DUE_PASSED · CANCELING · CANCELED
     */
    public static PreorderPayability blocked(Long preorderInternalId, String preorderId, Long customerId,
                                             PreorderProduct product, String reason) {
        String status = switch (reason) {
            case "NOT_YET_REGISTERED" -> "PENDING_SYNC";
            case "DUE_PASSED" -> "PAYABLE";
            default -> reason;
        };
        return payability(preorderInternalId, preorderId, customerId, product, status, false, reason);
    }

    private static PreorderPayability payability(Long preorderInternalId, String preorderId, Long customerId,
                                                 PreorderProduct product, String status, boolean payable,
                                                 String reason) {
        Instant payableFrom = "PENDING_SYNC".equals(status) ? null
                : Instant.now().minus("DUE_PASSED".equals(reason) ? PAYMENT_WINDOW.plusHours(1) : PAYABLE_FOR);
        return new PreorderPayability(preorderId, preorderInternalId, customerId, product.productId(),
                product.optionId(), OrderFixtures.PRODUCT_TITLE, OrderFixtures.OPTION_TITLE, OrderFixtures.UNIT_PRICE,
                status, payableFrom, payableFrom == null ? null : payableFrom.plus(PAYMENT_WINDOW), payable, reason);
    }

    public static void stub(PreorderClient client, PreorderPayability payability) {
        given(client.getPayability(eq(payability.preorderId()), any())).willReturn(ApiResponse.ok(payability));
    }

    /** 4xx(401 토큰 거절 · 403 남의 예약 등). 본문 없음 — 404 는 본문의 code 로 나뉘므로 아래 둘을 쓴다. */
    public static void stubClientError(PreorderClient client, String preorderId, HttpStatus status) {
        given(client.getPayability(eq(preorderId), any()))
                .willThrow(HttpClientErrorException.create(status, status.getReasonPhrase(), null, null, null));
    }

    /** 예약 없음. preorder 는 404 + error.code PREORDER_NOT_FOUND 로 답한다. */
    public static void stubPreorderNotFound(PreorderClient client, String preorderId) {
        given(client.getPayability(eq(preorderId), any())).willThrow(notFound("PREORDER_NOT_FOUND"));
    }

    /** 경로 없음(배포 순서 어긋남 · 잘못된 주소). preorder 공통 처리기는 404 + error.code NOT_FOUND 로 답한다. */
    public static void stubRouteNotFound(PreorderClient client, String preorderId) {
        given(client.getPayability(eq(preorderId), any())).willThrow(notFound("NOT_FOUND"));
    }

    /**
     * preorder 의 실패 봉투를 실은 404. RestClient 가 만드는 예외처럼 본문을 봉투로 읽을 수 있게 변환 함수를 단다
     * (응답 본문 변환은 PreorderClientTest 가 실제 클라이언트로 확인한다).
     */
    public static HttpClientErrorException notFound(String code) {
        byte[] body = """
                {"success":false,"data":null,"error":{"code":"%s","message":"없음","details":null},
                 "timestamp":"2026-09-03T02:00:00Z","traceId":"t-1"}
                """.formatted(code).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpClientErrorException e = HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", headers, body,
                StandardCharsets.UTF_8);
        JsonMapper mapper = JsonMapper.builder().build();
        e.setBodyConvertFunction(type -> mapper.readValue(body, mapper.constructType(type.getType())));
        return e;
    }

    public static void stubServerError(PreorderClient client, String preorderId, HttpStatus status) {
        given(client.getPayability(eq(preorderId), any()))
                .willThrow(HttpServerErrorException.create(status, status.getReasonPhrase(), null, null, null));
    }

    /** 읽기 타임아웃. RestClient 는 I/O 실패를 ResourceAccessException 으로 올린다. */
    public static void stubTimeout(PreorderClient client, String preorderId) {
        given(client.getPayability(eq(preorderId), any())).willThrow(new ResourceAccessException("Read timed out"));
    }
}
