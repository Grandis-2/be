package com.grandis.nova.preorder.integration.order;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.client.InternalCallFailures;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.integration.Dependencies;
import com.grandis.nova.preorder.integration.DependencyGuard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.UUID;

/**
 * 취소 사전 확인. order 가 답하지 못하면 취소를 시작하지 않는다 — "불가" 로 단정하지도, 확인 없이 진행하지도 않는다.
 *
 * - 401: 사용자 토큰 문제라 사용자에게도 401
 * - 403: 토큰 주인이 주문 회원이 아니다. 남의 것은 존재를 숨기므로 404
 * - 그 밖의 4xx · 읽을 수 없는 응답: 다시 불러도 같은 결과인 연동 오류 — 500(문구는 공통 처리기가 숨긴다)
 * - 타임아웃 · 연결 실패 · 5xx: 일시 장애 — 503, 예약 상태는 바뀌지 않는다
 */
@Component
public class OrderCancelabilityChecker {

    static final String DEPENDENCY = Dependencies.ORDER;

    private final OrderClient orderClient;
    private final DependencyGuard dependencyGuard;

    public OrderCancelabilityChecker(OrderClient orderClient, DependencyGuard dependencyGuard) {
        this.orderClient = orderClient;
        this.dependencyGuard = dependencyGuard;
    }

    /** @throws BusinessException 배송이 시작됐으면 PREORDER_NOT_CANCELABLE */
    public void requireCancelable(UUID preorderInternalId) {
        Cancelability cancelability = fetch(preorderInternalId);
        if (!cancelability.cancelable()) {
            throw new BusinessException(PreorderErrorCode.PREORDER_NOT_CANCELABLE,
                    Map.of("reason", "orderStatus=" + cancelability.orderStatus()));
        }
    }

    private Cancelability fetch(UUID preorderInternalId) {
        Cancelability answer;
        try {
            answer = dependencyGuard.call(DEPENDENCY, () -> orderClient.getCancelability(preorderInternalId))
                    .data();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
                throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
            }
            if (e.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN)) {
                throw new BusinessException(PreorderErrorCode.PREORDER_NOT_FOUND);
            }
            throw InternalCallFailures.integrationError(DEPENDENCY, "preorderInternalId=" + preorderInternalId, e);
        } catch (RestClientException e) {
            if (InternalCallFailures.isUnreadableResponse(e)) {
                throw InternalCallFailures.unreadableResponse(DEPENDENCY, "preorderInternalId=" + preorderInternalId, e);
            }
            throw dependencyGuard.callFailed(DEPENDENCY, "preorderInternalId=" + preorderInternalId, e);
        }
        if (answer == null) {
            throw new IllegalStateException("order 가 취소 가능 판정 없이 응답했다: preorderInternalId=" + preorderInternalId);
        }
        return answer;
    }
}
