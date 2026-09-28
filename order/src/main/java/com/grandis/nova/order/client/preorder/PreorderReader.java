package com.grandis.nova.order.client.preorder;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.order.client.InternalCallFailures;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * 주문에 쓸 예약. 부를 때마다 preorder 에 묻는다 — 상태 · 결제 가능 시각이 바뀌므로 캐시하지 않는다.
 * 트랜잭션 밖에서 부른다(DB 잠금을 preorder 응답 시간만큼 붙잡지 않게).
 */
@Component
public class PreorderReader {

    static final String DEPENDENCY = "preorder";

    private final PreorderClient preorderClient;

    public PreorderReader(PreorderClient preorderClient) {
        this.preorderClient = preorderClient;
    }

    /**
     * 그 토큰의 예약. 없으면 비어 있다.
     *
     * @throws BusinessException     DEPENDENCY_UNAVAILABLE — 타임아웃 · 연결 실패 · 5xx
     * @throws IllegalStateException 404 외의 4xx — 계약 불일치 같은 연동 오류(500)
     */
    public Optional<PreorderSnapshot> find(String preorderToken) {
        try {
            return Optional.ofNullable(preorderClient.getPreorder(preorderToken).data());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            // 로그에 토큰을 남기지 않는다. 예약 토큰은 공개 식별자지만 본인 확인 전이라 남의 것일 수 있다.
            throw InternalCallFailures.integrationError(DEPENDENCY, "getPreorder", e);
        } catch (RestClientException e) {
            throw InternalCallFailures.unavailable(DEPENDENCY, "getPreorder", e);
        }
    }
}
