package com.grandis.nova.order.client.preorder;

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

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 주문에 쓸 예약의 결제 가능 확인. 부를 때마다 preorder 에 묻는다 — 상태 · 기한이 바뀌므로 캐시하지 않는다.
 * 트랜잭션 밖에서 부른다(DB 잠금을 preorder 응답 시간만큼 붙잡지 않게).
 *
 * 주문 생성은 프론트가 보낸 예약 UUID 로({@link #find}), 결제 재확인은 주문에 저장한 예약 내부 id 로({@link #findById}) 묻는다.
 * 두 입구는 응답 · 오류가 같다.
 *
 * 사용자의 액세스 토큰을 Authorization: Bearer 로 그대로 싣는다(헤더 값은 여기서 만든다). 토큰은 로그 · 예외 메시지에 싣지 않는다.
 * 예약 UUID · 내부 id 는 장애 로그의 요청 URL(원인 예외 메시지)에 남을 수 있다 — 어느 호출이 실패했는지 추적하는 데 쓴다.
 */
@Component
public class PreorderReader {

    private static final Logger log = LoggerFactory.getLogger(PreorderReader.class);

    static final String DEPENDENCY = "preorder";
    static final String OPERATION = "getPayability";
    static final String OPERATION_BY_ID = "getPayabilityById";
    /** preorder PreorderErrorCode.PREORDER_NOT_FOUND. 경로가 없을 때의 404 는 공통 NOT_FOUND 라 이것으로 가린다. */
    static final String PREORDER_NOT_FOUND = "PREORDER_NOT_FOUND";

    private final PreorderClient preorderClient;

    public PreorderReader(PreorderClient preorderClient) {
        this.preorderClient = preorderClient;
    }

    /**
     * 그 예약의 결제 가능 확인. 없거나 남의 예약(preorder 403)이면 비어 있다 — 남의 예약은 존재를 숨긴다.
     *
     * @param sessionToken 사용자가 보낸 액세스 토큰 원문(접두어 없음). null 이면 싣지 않는다(preorder 가 401)
     * @throws BusinessException     UNAUTHENTICATED — preorder 가 토큰을 거절(401).
     *                               DEPENDENCY_UNAVAILABLE — 타임아웃 · 연결 실패 · 5xx
     * @throws IllegalStateException 연동 오류(500) — 그 밖의 4xx, 예약 없음이 아닌 404(경로 없음 · 잘못된 주소),
     *                               읽을 수 없는 응답(본문 · Content-Type 이 계약과 다름), 물은 예약이 아닌 응답
     */
    public Optional<PreorderPayability> find(String preorderId, String sessionToken) {
        Optional<PreorderPayability> found = call(OPERATION,
                () -> preorderClient.getPayability(preorderId, authorization(sessionToken)));
        found.ifPresent(payability -> requireSamePreorder(OPERATION, preorderId, payability.preorderId()));
        return found;
    }

    /**
     * 예약 내부 id 로 묻는 같은 확인 — 결제 재확인이 쓴다. 비어 있는 경우 · 예외는 {@link #find} 와 같다.
     *
     * @param preorderInternalId 주문에 저장한 예약 내부 id(orders.preorder_id)
     */
    public Optional<PreorderPayability> findById(UUID preorderInternalId, String sessionToken) {
        Optional<PreorderPayability> found = call(OPERATION_BY_ID,
                () -> preorderClient.getPayabilityById(preorderInternalId, authorization(sessionToken)));
        found.ifPresent(payability -> requireSamePreorder(OPERATION_BY_ID, preorderInternalId, payability.preorderInternalId()));
        return found;
    }

    /**
     * 응답의 예약이 물은 예약인가. 주문은 응답의 내부 id 를 저장하고 그 예약의 상태로 결제를 판정하므로, 다른 예약의 응답을 쓰면
     * 엉뚱한 예약을 가리키는 주문이 생기거나 남의 상태로 결제를 허락한다. 다시 물어도 같으므로 연동 오류(500)다.
     * 예약 UUID 는 대소문자까지 같아야 한다 — preorders.preorder_token 은 utf8mb4_bin 이다.
     */
    private static void requireSamePreorder(String operation, Object requested, Object returned) {
        if (!requested.equals(returned)) {
            log.error("{} 연동 오류 {} 물은 예약과 다른 응답 requested={} returned={}",
                    DEPENDENCY, operation, requested, returned);
            throw new IllegalStateException(DEPENDENCY + " 연동 오류: 물은 예약과 다른 응답");
        }
    }

    private Optional<PreorderPayability> call(String operation, Supplier<ApiResponse<PreorderPayability>> request) {
        try {
            return Optional.ofNullable(request.get().data());
        } catch (HttpClientErrorException.NotFound e) {
            if (isPreorderNotFound(e)) {
                return Optional.empty();
            }
            // 배포 순서 어긋남 · base-url 오류면 모든 주문이 "예약 없음" 으로 보인다. 조용히 삼키지 않는다.
            throw InternalCallFailures.integrationError(DEPENDENCY, operation, e);
        } catch (HttpClientErrorException.Forbidden e) {
            // 소유자 아님과 preorder 보안 체인 거절이 같은 FORBIDDEN 이라 구분할 수 없다. 권한 규칙이 어긋나 모든 주문이
            // 404 로 보일 때 흔적이 남도록 한 줄 남긴다(토큰 · 예약 UUID 는 싣지 않는다).
            log.info("{} {} 403 — 예약 없음으로 숨김", DEPENDENCY, operation);
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        } catch (HttpClientErrorException e) {
            throw InternalCallFailures.integrationError(DEPENDENCY, operation, e);
        } catch (RestClientException e) {
            if (InternalCallFailures.isUnreadableResponse(e)) {
                throw InternalCallFailures.unreadableResponse(DEPENDENCY, operation, e);
            }
            throw InternalCallFailures.unavailable(DEPENDENCY, operation, e);
        }
    }

    private static String authorization(String sessionToken) {
        return sessionToken == null ? null : BearerTokens.value(sessionToken);
    }

    /** 404 본문이 preorder 의 "예약 없음" 봉투인지. 읽을 수 없는 본문은 예약 없음으로 보지 않는다. */
    private static boolean isPreorderNotFound(HttpClientErrorException e) {
        try {
            ApiResponse<?> body = e.getResponseBodyAs(ApiResponse.class);
            return body != null && body.error() != null && PREORDER_NOT_FOUND.equals(body.error().code());
        } catch (RuntimeException unreadable) {
            return false;
        }
    }
}
