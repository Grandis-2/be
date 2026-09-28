package com.grandis.nova.order.client.preorder;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.client.InternalCallFailures;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * 주문에 쓸 예약의 결제 가능 확인. 부를 때마다 preorder 에 묻는다 — 상태 · 기한이 바뀌므로 캐시하지 않는다.
 * 트랜잭션 밖에서 부른다(DB 잠금을 preorder 응답 시간만큼 붙잡지 않게).
 *
 * 세션 토큰은 로그 · 예외 메시지에 싣지 않는다. 예약 UUID 는 공개 식별자라 장애 로그의 요청 URL(원인 예외 메시지)에
 * 남을 수 있다 — 어느 호출이 실패했는지 추적하는 데 쓴다.
 */
@Component
public class PreorderReader {

    static final String DEPENDENCY = "preorder";
    static final String OPERATION = "getPayability";

    private final PreorderClient preorderClient;

    public PreorderReader(PreorderClient preorderClient) {
        this.preorderClient = preorderClient;
    }

    /**
     * 그 예약의 결제 가능 확인. 없거나 남의 예약(preorder 403)이면 비어 있다 — 남의 예약은 존재를 숨긴다.
     *
     * @param sessionToken 사용자가 보낸 세션 토큰 그대로
     * @throws BusinessException     UNAUTHENTICATED — preorder 가 토큰을 거절(401).
     *                               DEPENDENCY_UNAVAILABLE — 타임아웃 · 연결 실패 · 5xx
     * @throws IllegalStateException 그 밖의 4xx — 계약 불일치 같은 연동 오류(500)
     */
    public Optional<PreorderPayability> find(String preorderId, String sessionToken) {
        try {
            return Optional.ofNullable(preorderClient.getPayability(preorderId, sessionToken).data());
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden e) {
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        } catch (HttpClientErrorException e) {
            throw InternalCallFailures.integrationError(DEPENDENCY, OPERATION, e);
        } catch (RestClientException e) {
            throw InternalCallFailures.unavailable(DEPENDENCY, OPERATION, e);
        }
    }
}
