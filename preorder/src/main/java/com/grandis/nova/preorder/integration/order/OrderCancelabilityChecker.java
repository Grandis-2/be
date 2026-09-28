package com.grandis.nova.preorder.integration.order;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.integration.InternalCallFailures;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * 취소 사전 확인. order 가 답하지 못하면 취소를 시작하지 않는다 — "불가" 로 단정하지도, 확인 없이 진행하지도 않는다.
 *
 * - 401: 사용자 토큰 문제라 사용자에게도 401
 * - 403: 토큰 주인이 주문 회원이 아니다. 남의 것은 존재를 숨기므로 404
 * - 그 밖의 4xx: 다시 불러도 같은 결과인 연동 오류 — 500(문구는 공통 처리기가 숨긴다)
 * - 타임아웃 · 연결 실패 · 5xx: 일시 장애 — 503, 예약 상태는 바뀌지 않는다
 */
@Component
public class OrderCancelabilityChecker {

    static final String DEPENDENCY = "order";

    private final OrderClient orderClient;

    public OrderCancelabilityChecker(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    /** @throws BusinessException 배송이 시작됐으면 PREORDER_NOT_CANCELABLE */
    public void requireCancelable(String preorderToken, String sessionToken) {
        Cancelability cancelability = fetch(preorderToken, sessionToken);
        if (!cancelability.cancelable()) {
            throw new BusinessException(PreorderErrorCode.PREORDER_NOT_CANCELABLE,
                    Map.of("reason", "orderStatus=" + cancelability.orderStatus()));
        }
    }

    private Cancelability fetch(String preorderToken, String sessionToken) {
        Cancelability answer;
        try {
            answer = orderClient.getCancelability(preorderToken, sessionToken).data();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
                throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
            }
            if (e.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN)) {
                throw new BusinessException(PreorderErrorCode.PREORDER_NOT_FOUND);
            }
            throw InternalCallFailures.integrationError(DEPENDENCY, "preorderId=" + preorderToken, e);
        } catch (RestClientException e) {
            throw InternalCallFailures.unavailable(DEPENDENCY, "preorderId=" + preorderToken, e);
        }
        if (answer == null) {
            throw new IllegalStateException("order 가 취소 가능 판정 없이 응답했다: preorderId=" + preorderToken);
        }
        return answer;
    }
}
