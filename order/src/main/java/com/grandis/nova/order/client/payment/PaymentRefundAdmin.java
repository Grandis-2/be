package com.grandis.nova.order.client.payment;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.common.web.client.InternalCallFailures;
import com.grandis.nova.order.OrderErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * payment 의 관리자 환불 조치 API 를 관리자 대신 부른다. 관리자 액세스 토큰을 Authorization: Bearer 로 그대로 싣는다 — payment 가 역할을 다시
 * 보고, 조치한 사람을 그 토큰의 주체(order 의 Authentication 이름과 같다)로 남긴다. 토큰은 로그 · 예외에 싣지 않는다.
 *
 * 실패는 DLQ 중계({@link PaymentDeadLetters})와 같이 가른다. payment 의 환불 조치 업무 오류(REFUND_*: 404 · 409 · 503)는 같은 코드 · 상태 ·
 * details 로 넘긴다 — 결제사 조회 불명(503 REFUND_PROVIDER_UNAVAILABLE)도 "아무것도 바꾸지 않았다" 는 뜻이라 일반 연동 장애와 가른다.
 * payment 의 401 은 503, 그 밖의 4xx · 읽을 수 없는 응답은 연동 오류(500), 타임아웃 · 연결 실패 · 그 밖의 5xx 는 503 이다. 조치는 결제사
 * 조회를 기다려 읽기 기한을 70초로 둔다(payment-refund-admin 그룹).
 *
 * 응답을 받지 못한 조치는 반영됐을 수 있다. 앞단 CloudFront 의 원본 읽기 기한이 60초라(ALB 유휴 65초) 조치가 60초를 넘으면 관리자는 504 를
 * 받지만 order · payment 는 끝까지 진행한다. 그래서 503 · 504 뒤에는 목록을 다시 조회해 지금 상태를 본다 — 같은 조치를 다시 눌러도 본 거래가
 * 바뀌었으면 REFUND_STATE_CHANGED 로 거절돼 두 번 반영되지 않는다.
 */
@Component
public class PaymentRefundAdmin {

    private static final Logger log = LoggerFactory.getLogger(PaymentRefundAdmin.class);

    static final String DEPENDENCY = "payment";
    /** payment 가 같은 이름 · 상태로 내는 환불 조치 업무 오류. */
    private static final Map<String, OrderErrorCode> PASSED_THROUGH = Map.of(
            "REFUND_NOT_FOUND", OrderErrorCode.REFUND_NOT_FOUND,
            "REFUND_ALREADY_COMPLETED", OrderErrorCode.REFUND_ALREADY_COMPLETED,
            "REFUND_IN_PROGRESS", OrderErrorCode.REFUND_IN_PROGRESS,
            "REFUND_STATE_CHANGED", OrderErrorCode.REFUND_STATE_CHANGED,
            "REFUND_NOT_RETRYABLE", OrderErrorCode.REFUND_NOT_RETRYABLE,
            "REFUND_NOT_CANCELED_AT_PROVIDER", OrderErrorCode.REFUND_NOT_CANCELED_AT_PROVIDER,
            "REFUND_PROVIDER_UNAVAILABLE", OrderErrorCode.REFUND_PROVIDER_UNAVAILABLE);

    private final PaymentRefundAdminClient client;

    public PaymentRefundAdmin(PaymentRefundAdminClient client) {
        this.client = client;
    }

    /**
     * @param orderIds 비어 있으면 payment 쪽에서 멈췄을 수 있는 주문, 있으면 그 주문들의 판정
     * @param sessionToken 관리자가 보낸 액세스 토큰 원문(접두어 없음). null 이면 싣지 않는다(payment 가 401 → 503)
     */
    public List<PaymentRefundIssue> issues(List<UUID> orderIds, int staleHours, String sessionToken) {
        return call("refund-issues.list", () -> client.issues(orderIds.isEmpty() ? null : orderIds, staleHours,
                authorization(sessionToken)));
    }

    public PaymentRefundAdminResult retry(UUID orderId, PaymentRefundAdminRequest request, String sessionToken) {
        return call("refunds.retry", () -> client.retry(orderId, request, authorization(sessionToken)));
    }

    public PaymentRefundAdminResult manualComplete(UUID orderId, PaymentRefundAdminRequest request, String sessionToken) {
        return call("refunds.manual-complete", () -> client.manualComplete(orderId, request, authorization(sessionToken)));
    }

    private static <T> T call(String operation, Supplier<ApiResponse<T>> request) {
        ApiResponse<T> body;
        try {
            body = request.get();
        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("{} {} 전달 토큰 거절(401) — payment 의 JWKS · jwt 설정 · 폐기 조회 확인", DEPENDENCY, operation);
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (HttpClientErrorException e) {
            throw passedThrough(e).orElseThrow(() -> InternalCallFailures.integrationError(DEPENDENCY, operation, e));
        } catch (HttpServerErrorException e) {
            throw passedThrough(e).orElseThrow(() -> InternalCallFailures.unavailable(DEPENDENCY, operation, e));
        } catch (RestClientException e) {
            if (InternalCallFailures.isUnreadableResponse(e)) {
                throw InternalCallFailures.unreadableResponse(DEPENDENCY, operation, e);
            }
            throw InternalCallFailures.unavailable(DEPENDENCY, operation, e);
        }
        if (body == null || body.data() == null) {
            log.error("{} 연동 오류 {} 본문 없는 응답", DEPENDENCY, operation);
            throw new IllegalStateException(DEPENDENCY + " 연동 오류: 본문 없는 응답");
        }
        return body.data();
    }

    /** 코드는 응답 본문에서 읽는다 — 프레임워크 · 앞단이 낸 오류(본문 없는 403 · 게이트웨이 502 등)는 여기 들지 않는다. */
    private static Optional<BusinessException> passedThrough(HttpStatusCodeException e) {
        ApiResponse<?> body;
        try {
            body = e.getResponseBodyAs(ApiResponse.class);
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
        if (body == null || body.error() == null) {
            return Optional.empty();
        }
        OrderErrorCode code = PASSED_THROUGH.get(body.error().code());
        if (code == null || code.status() != e.getStatusCode().value()) {
            return Optional.empty();
        }
        return Optional.of(new BusinessException(code, body.error().details()));
    }

    private static String authorization(String sessionToken) {
        return sessionToken == null ? null : BearerTokens.value(sessionToken);
    }
}
