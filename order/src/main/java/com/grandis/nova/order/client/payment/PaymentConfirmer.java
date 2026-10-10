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
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;

/**
 * 결제 대상(주문 · 응모)의 결제를 payment 에 승인시킨다. 트랜잭션 밖에서 부른다(payment 가 토스를 최대 60초 기다린다).
 *
 * 실패를 "주문을 결제 대기로 되돌려도 되는가" 로 가른다. 전송 계층 신호(4xx 계열 · 연결 실패)는 이번 요청에 대한 것일 뿐
 * 결제창의 상태가 아니다 — 앞선 요청이 같은 결제창을 이미 시작해 토스에 보냈을 수 있다. 그래서 되돌림은 payment 의 업무 코드가
 * "이 결제창은 앞으로도 시작될 수 없다" 고 확언할 때만 한다(D17).
 * - NotStartable: PAYMENT_ATTEMPT_NOT_FOUND · PAYMENT_AMOUNT_MISMATCH.
 * - Unanswered: 그 밖의 4xx(401 · 교착 · 계약 밖) · 연결을 맺지 못함. 주문은 그대로 두고 오류로 답한다(다시 부르면 회수된다).
 * - Pending: 읽기 기한 초과 · 5xx · 계약과 다른 200. 시작했을 수 있다 — "확인 중"으로 답하고 결과 이벤트를 기다린다.
 *
 * payment 의 401 은 사용자 401 이 아니라 503 이다(PaymentPreparer 와 같은 근거). 결제 키 · 토큰은 로그 · 예외에 싣지 않는다.
 */
@Component
public class PaymentConfirmer {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfirmer.class);

    static final String DEPENDENCY = "payment";
    static final String OPERATION = "confirm";
    static final String CHECK = "confirm-check";

    private final PaymentConfirmClient client;

    public PaymentConfirmer(PaymentConfirmClient client) {
        this.client = client;
    }

    /**
     * @param providerOrderId 승인할 결제창(결제사 주문 번호)
     * @param paymentKey      결제창이 돌려준 결제 키
     * @param sessionToken    사용자가 보낸 액세스 토큰 원문(접두어 없음). null 이면 싣지 않는다(payment 가 401)
     * @param startAllowed    false 면 payment 가 아직 시작하지 않은 결제창을 시작하지 않는다(결과 회수만)
     */
    public PaymentConfirmation confirm(PayableTarget target, String providerOrderId, String paymentKey, String sessionToken,
                                       boolean startAllowed) {
        ApiResponse<ConfirmReply> body;
        try {
            body = client.confirm(providerOrderId, ConfirmRequest.of(target, paymentKey, startAllowed),
                    authorization(sessionToken));
        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("{} {} 전달 토큰 거절(401) — payment 의 JWKS · jwt 설정 · 폐기 조회 확인 target={}",
                    DEPENDENCY, OPERATION, target);
            return new PaymentConfirmation.Unanswered(new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        } catch (HttpClientErrorException e) {
            return rejected(target, providerOrderId, e);
        } catch (ResourceAccessException e) {
            if (isConnectFailure(e)) {
                return new PaymentConfirmation.Unanswered(InternalCallFailures.unavailable(DEPENDENCY,
                        OPERATION + " 연결 실패 target=" + target, e));
            }
            log.warn("{} {} 결과 모름 — 응답을 받지 못함 target={} providerOrderId={} cause={}", DEPENDENCY, OPERATION,
                    target, providerOrderId, rootCause(e).getClass().getName());
            return new PaymentConfirmation.Pending();
        } catch (RestClientException e) {
            log.warn("{} {} 결과 모름 — 5xx · 읽을 수 없는 응답 target={} providerOrderId={}", DEPENDENCY, OPERATION,
                    target, providerOrderId, e);
            return new PaymentConfirmation.Pending();
        }
        return read(target, providerOrderId, body == null ? null : body.data());
    }

    /**
     * 주문을 승인 중으로 바꾸기 전에, payment 가 이 결제창을 이 주문의 것으로 아는지 묻고 결제창을 확보한다(시작 금지 + 확보 — 아무것도
     * 시작하지 않는다). 승인 중 주문의 결제창 번호를 payment 가 모르면, 응답을 잃었을 때 payment 의 만료 · 복구가 그 주문을 풀지 못해
     * 승인 중에 갇힌다. 확보는 payment 의 만료를 지금부터 다시 재게 해, 이 확인과 승인 중 전환 사이에 만료가 끼어 그 결과(이벤트)가
     * 주문이 승인 중이 되기 전에 도착해 버려지는 경합을 막는다 — 만료가 먼저였으면 이 확인이 거절(만료)로 본다.
     *
     * 확인이 시작하지 않으므로 답을 받지 못한 경우(연결 실패 · 읽기 기한 · 5xx · 계약과 다른 200)는 모두 "이번 요청 실패"다 —
     * {@link #confirm} 과 달리 확인 중(Pending)으로 두지 않는다.
     *
     * @return Pending(아직 시작 전 · 진행 중) · Approved · Declined(끝난 결제창) · NotStartable(번호 없음 · 다른 주문) · Unanswered
     */
    public PaymentConfirmation check(PayableTarget target, String providerOrderId, String paymentKey, String sessionToken) {
        ApiResponse<ConfirmReply> body;
        try {
            body = client.confirm(providerOrderId, ConfirmRequest.check(target, paymentKey), authorization(sessionToken));
        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("{} {} 전달 토큰 거절(401) — payment 의 JWKS · jwt 설정 · 폐기 조회 확인 target={}",
                    DEPENDENCY, CHECK, target);
            return new PaymentConfirmation.Unanswered(new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        } catch (HttpClientErrorException e) {
            return rejected(target, providerOrderId, e);
        } catch (RestClientException e) {
            if (InternalCallFailures.isUnreadableResponse(e)) {
                return new PaymentConfirmation.Unanswered(InternalCallFailures.unreadableResponse(DEPENDENCY,
                        CHECK + " target=" + target, e));
            }
            return new PaymentConfirmation.Unanswered(InternalCallFailures.unavailable(DEPENDENCY,
                    CHECK + " 응답 없음 target=" + target, e));
        }
        ConfirmReply reply = body == null ? null : body.data();
        if (!conforms(reply)) {
            log.error("{} 연동 오류 {} 계약과 다른 응답 target={} providerOrderId={} reply={}",
                    DEPENDENCY, CHECK, target, providerOrderId, reply);
            return new PaymentConfirmation.Unanswered(new IllegalStateException(DEPENDENCY + " 연동 오류: " + CHECK));
        }
        return read(target, providerOrderId, reply);
    }

    /** 200 인데 모양이 계약과 다르면 시작했을 수 있어 Pending 이다. 다시 불러도 같으니 ERROR 로 남긴다. */
    private static PaymentConfirmation read(PayableTarget target, String providerOrderId, ConfirmReply reply) {
        if (!conforms(reply)) {
            log.error("{} 연동 오류 {} 계약과 다른 응답 — 결과 모름으로 둔다 target={} providerOrderId={} reply={}",
                    DEPENDENCY, OPERATION, target, providerOrderId, reply);
            return new PaymentConfirmation.Pending();
        }
        return switch (reply.result()) {
            case APPROVED -> new PaymentConfirmation.Approved();
            case DECLINED -> new PaymentConfirmation.Declined(reply.declineReason());
            case PENDING -> new PaymentConfirmation.Pending();
        };
    }

    private static boolean conforms(ConfirmReply reply) {
        return reply != null && reply.result() != null
                && (reply.result() == ConfirmReply.Result.DECLINED) == (reply.declineReason() != null);
    }

    /**
     * payment 의 거절을 옮긴다. 되돌려도 되는 것은 결제창이 영영 시작될 수 없다는 업무 코드뿐이다 — 코드는 응답 본문에서 읽으므로
     * 프레임워크 · 앞단이 낸 4xx(401 · 403 · 라우팅 404 등 본문 없는 것)는 여기 들지 않는다.
     */
    private static PaymentConfirmation rejected(PayableTarget target, String providerOrderId, HttpClientErrorException e) {
        String code = errorCode(e);
        if ("PAYMENT_ATTEMPT_NOT_FOUND".equals(code)) {
            log.info("{} {} 결제창 없음 · 다른 대상의 결제창 target={} providerOrderId={}", DEPENDENCY, OPERATION,
                    target, providerOrderId);
            return new PaymentConfirmation.NotStartable(new BusinessException(OrderErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
        }
        if ("PAYMENT_AMOUNT_MISMATCH".equals(code)) {
            // 결제창이 저장 금액과 다른 금액으로 열렸다. 준비 API 는 늘 저장 금액으로 여므로 다른 경로로 연 결제창이다
            log.error("{} {} 결제창 금액이 저장 금액과 다름 target={} providerOrderId={}", DEPENDENCY, OPERATION,
                    target, providerOrderId);
            return new PaymentConfirmation.NotStartable(new BusinessException(OrderErrorCode.PAYMENT_AMOUNT_MISMATCH));
        }
        if ("PAYMENT_START_CONFLICT".equals(code)) {
            log.warn("{} {} 동시 시작과 교착 — 이번 요청은 시작하지 않음 target={}", DEPENDENCY, OPERATION, target);
            return new PaymentConfirmation.Unanswered(new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        }
        return new PaymentConfirmation.Unanswered(
                InternalCallFailures.integrationError(DEPENDENCY, OPERATION + " target=" + target, e));
    }

    private static String errorCode(HttpClientErrorException e) {
        try {
            ApiResponse<?> body = e.getResponseBodyAs(ApiResponse.class);
            return body == null || body.error() == null ? null : body.error().code();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static Throwable rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    /** 연결을 맺지 못했다 — 이번 요청은 나가지 않았다. 읽기 기한 초과와 달리 이번 요청으로는 시작되지 않았다. */
    private static boolean isConnectFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String authorization(String sessionToken) {
        return sessionToken == null ? null : BearerTokens.value(sessionToken);
    }
}
