package com.grandis.nova.order.client.payment;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.common.web.client.InternalCallFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * 결제 대상(주문 · 응모)의 결제창을 열 거래를 payment 에 만든다. 트랜잭션 밖에서 부른다(DB 잠금을 payment 응답 시간만큼 붙잡지 않게).
 *
 * 사용자의 액세스 토큰을 Authorization: Bearer 로 그대로 싣는다(헤더 값은 여기서 만든다). 토큰은 로그 · 예외 메시지에 싣지 않는다.
 * 응답이 없으면(타임아웃) payment 가 거래를 이미 만들었을 수 있다 — 버려진 PENDING 으로 남고 사용자가 다시 부른다.
 *
 * payment 의 401 은 사용자 401 이 아니라 503 으로 답한다. 같은 토큰을 order 의 인증 필터가 방금 통과시켰으므로, payment 가
 * 거절했다면 대개 payment 쪽 사정이다(JWKS 미수신 · 모르는 kid · 폐기 조회 실패 · issuer · audience 설정 어긋남). 사용자 401 로
 * 넘기면 프론트가 멀쩡한 세션을 재발급 · 로그아웃으로 다룬다. 토큰이 그사이 정말 만료 · 폐기됐다면 다시 부를 때 order 필터가
 * 401 로 답한다. 설정 어긋남은 503 이 계속되므로 일반 장애(WARN)와 가르도록 ERROR 로 남긴다 — 지속 비율에 경보를 건다.
 * preorder 클라이언트(PreorderReader)는 아직 401 을 사용자 401 로 넘긴다 — 같은 근거가 들어맞으며 별도로 정리한다.
 */
@Component
public class PaymentPreparer {

    private static final Logger log = LoggerFactory.getLogger(PaymentPreparer.class);

    static final String DEPENDENCY = "payment";
    static final String OPERATION = "openCapture";

    private final PaymentClient paymentClient;

    public PaymentPreparer(PaymentClient paymentClient) {
        this.paymentClient = paymentClient;
    }

    /**
     * @param target       결제 대상과 그 저장 금액
     * @param sessionToken 사용자가 보낸 액세스 토큰 원문(접두어 없음). null 이면 싣지 않는다(payment 가 401)
     * @throws BusinessException     DEPENDENCY_UNAVAILABLE — 타임아웃 · 연결 실패 · 5xx · payment 가 토큰을 거절(401)
     * @throws IllegalStateException 연동 오류(500) — 그 밖의 4xx, 읽을 수 없는 응답, 보낸 금액과 다른 응답
     */
    public PaymentAttempt openCapture(PayableTarget target, String sessionToken) {
        PaymentAttempt attempt = call(CaptureRequest.of(target), sessionToken);
        requireOpenedFor(target, attempt);
        return attempt;
    }

    /**
     * 결제창에 띄울 값이 보낸 저장 금액 그대로인가. 다른 금액을 프론트에 넘기면 사용자가 그 금액으로 결제창을 연다.
     * 다시 불러도 같으므로 연동 오류(500)다.
     */
    private static void requireOpenedFor(PayableTarget target, PaymentAttempt attempt) {
        if (attempt == null || attempt.providerOrderId() == null || attempt.amount() == null
                || attempt.amount().compareTo(target.amount()) != 0) {
            log.error("{} 연동 오류 {} 요청과 다른 응답 target={} amount={} returned={}",
                    DEPENDENCY, OPERATION, target, target.amount(), attempt);
            throw new IllegalStateException(DEPENDENCY + " 연동 오류: 요청과 다른 응답");
        }
    }

    private PaymentAttempt call(CaptureRequest request, String sessionToken) {
        try {
            ApiResponse<PaymentAttempt> body = paymentClient.openCapture(request, authorization(sessionToken));
            return body == null ? null : body.data();
        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("{} {} 전달 토큰 거절(401) — payment 의 JWKS · jwt 설정 · 폐기 조회 확인", DEPENDENCY, OPERATION);
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (HttpClientErrorException e) {
            throw InternalCallFailures.integrationError(DEPENDENCY, OPERATION, e);
        } catch (RestClientException e) {
            if (InternalCallFailures.isUnreadableResponse(e)) {
                throw InternalCallFailures.unreadableResponse(DEPENDENCY, OPERATION, e);
            }
            throw InternalCallFailures.unavailable(DEPENDENCY, OPERATION, e);
        }
    }

    private static String authorization(String sessionToken) {
        return sessionToken == null ? null : BearerTokens.value(sessionToken);
    }
}
