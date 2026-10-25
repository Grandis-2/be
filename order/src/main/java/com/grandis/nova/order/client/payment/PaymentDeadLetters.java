package com.grandis.nova.order.client.payment;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.common.web.client.InternalCallFailures;
import com.grandis.nova.order.OrderErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * payment 의 관리자 DLQ API 를 관리자 대신 부른다. 관리자 액세스 토큰을 Authorization: Bearer 로 그대로 싣는다 — payment 가 역할을 다시
 * 보고, 되돌리기 · 버리기를 한 사람을 그 토큰의 주체(order 의 Authentication 이름과 같다)로 남긴다. 토큰은 로그 · 예외에 싣지 않는다.
 *
 * 실패는 기존 내부 호출과 같이 가른다. payment 의 DLQ 업무 오류(DEAD_LETTER_*: 404 · 409)는 같은 코드 · 상태 · details 로 넘긴다.
 * payment 의 401 은 503(PaymentPreparer 와 같은 근거), 그 밖의 4xx · 읽을 수 없는 응답은 연동 오류(500), 타임아웃 · 연결 실패 · 5xx 는
 * 503 이다. 읽기 기한은 payment 의 SQS 보내기 한 번보다 길게 둔다(payment-admin 그룹). 그래도 응답을 받지 못하면 payment 가 이미
 * 보냈을 수 있다 — 상세의 상태로 확인한다(다시 눌러도 한 번만 보낸다).
 */
@Component
public class PaymentDeadLetters {

    private static final Logger log = LoggerFactory.getLogger(PaymentDeadLetters.class);

    static final String DEPENDENCY = "payment";
    /** payment 가 같은 이름 · 상태로 내는 DLQ 업무 오류. */
    private static final Map<String, OrderErrorCode> PASSED_THROUGH = Map.of(
            "DEAD_LETTER_NOT_FOUND", OrderErrorCode.DEAD_LETTER_NOT_FOUND,
            "DEAD_LETTER_NOT_REDRIVABLE", OrderErrorCode.DEAD_LETTER_NOT_REDRIVABLE,
            "DEAD_LETTER_NOT_DISCARDABLE", OrderErrorCode.DEAD_LETTER_NOT_DISCARDABLE,
            "REDRIVE_BATCH_NOT_FOUND", OrderErrorCode.REDRIVE_BATCH_NOT_FOUND);

    private final PaymentDeadLetterClient client;

    public PaymentDeadLetters(PaymentDeadLetterClient client) {
        this.client = client;
    }

    /** @param sessionToken 관리자가 보낸 액세스 토큰 원문(접두어 없음). null 이면 싣지 않는다(payment 가 401 → 503) */
    public OffsetPage<PaymentDeadLetterSummary> list(PaymentDeadLetterStatus status, String eventType,
                                                     PaymentDeadLetterFailureReason failureReason, UUID orderId,
                                                     Instant from, Instant to, int page, int size,
                                                     String sessionToken) {
        return call("dead-letters.list", () -> client.list(status, eventType, failureReason, orderId, from, to, page,
                size, authorization(sessionToken)));
    }

    public PaymentDeadLetter get(UUID deadLetterId, String sessionToken) {
        return call("dead-letters.get", () -> client.get(deadLetterId, authorization(sessionToken)));
    }

    public PaymentDeadLetterSummary redrive(UUID deadLetterId, String sessionToken) {
        return call("dead-letters.redrive", () -> client.redrive(deadLetterId, authorization(sessionToken)));
    }

    public PaymentDeadLetterBatch redriveBatch(PaymentDeadLetterBatchRequest request, String sessionToken) {
        return call("dead-letters.redrive-batch", () -> client.redriveBatch(request, authorization(sessionToken)));
    }

    /** 없거나 하루가 지난 batchId 는 payment 의 REDRIVE_BATCH_NOT_FOUND(404) 를 그대로 넘긴다. */
    public PaymentDeadLetterBatchProgress redriveBatchProgress(UUID batchId, String sessionToken) {
        return call("dead-letters.redrive-batch-progress",
                () -> client.redriveBatchProgress(batchId, authorization(sessionToken)));
    }

    /**
     * 버린 메시지는 다시 오지 않는다 — order 에는 환불 요청을 다시 보내는 길이 없어, 버린 환불 요청의 주문은 취소 중에 머문다.
     * 사람이 직접 환불을 확인해야 하고, payment 가 ERROR 로그 · payment.dlq.discarded 로 드러낸다.
     */
    public PaymentDeadLetter discard(UUID deadLetterId, PaymentDeadLetterDiscardRequest request, String sessionToken) {
        return call("dead-letters.discard", () -> client.discard(deadLetterId, request, authorization(sessionToken)));
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

    /** 코드는 응답 본문에서 읽는다 — 프레임워크 · 앞단이 낸 4xx(본문 없는 403 · 라우팅 404 등)는 여기 들지 않는다. */
    private static Optional<BusinessException> passedThrough(HttpClientErrorException e) {
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
