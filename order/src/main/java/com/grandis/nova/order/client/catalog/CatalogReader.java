package com.grandis.nova.order.client.catalog;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.client.InternalCallFailures;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 장바구니에 쓸 옵션 정보. 부를 때마다 catalog 에 묻는다 — 가격 · 판매 상태가 바뀌므로 캐시하지 않는다.
 * 트랜잭션 밖에서 부른다(DB 잠금을 catalog 응답 시간만큼 붙잡지 않게).
 *
 * 사용자의 액세스 토큰을 Authorization: Bearer 로 그대로 싣는다. 토큰은 로그 · 예외 메시지에 싣지 않는다.
 */
@Component
public class CatalogReader {

    static final String DEPENDENCY = "catalog";
    static final String OPERATION = "getOptions";

    private final CatalogClient catalogClient;

    public CatalogReader(CatalogClient catalogClient) {
        this.catalogClient = catalogClient;
    }

    /**
     * 옵션 id → 사실. 없는 옵션은 빠진다(호출자가 "판매 종료" 로 판정). 빈 묶음이면 부르지 않는다.
     *
     * @param sessionToken 사용자가 보낸 액세스 토큰 원문(접두어 없음). null 이면 싣지 않는다(catalog 가 401)
     * @throws BusinessException     UNAUTHENTICATED — catalog 가 토큰을 거절(401). DEPENDENCY_UNAVAILABLE — 타임아웃 · 연결 실패 · 5xx
     * @throws IllegalStateException 연동 오류(500) — 그 밖의 4xx(경로 없음 · 계약 어긋남), 읽을 수 없는 응답
     */
    public Map<UUID, CatalogOption> find(Collection<UUID> optionIds, String sessionToken) {
        Map<UUID, CatalogOption> byId = new LinkedHashMap<>();
        if (optionIds.isEmpty()) {
            return byId;
        }
        CatalogOptions options;
        try {
            options = catalogClient.getOptions(optionIds, authorization(sessionToken)).data();
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        } catch (HttpClientErrorException e) {
            // 400(계약 어긋남) · 403 · 404(경로 없음 · 배포 순서 어긋남)는 모두 연동 오류다 — 조용히 "없음" 으로 읽으면 모든 장바구니가 판매 종료로 보인다
            throw InternalCallFailures.integrationError(DEPENDENCY, OPERATION, e);
        } catch (RestClientException e) {
            if (InternalCallFailures.isUnreadableResponse(e)) {
                throw InternalCallFailures.unreadableResponse(DEPENDENCY, OPERATION, e);
            }
            throw InternalCallFailures.unavailable(DEPENDENCY, OPERATION, e);
        }
        if (options != null) {
            options.items().forEach(option -> byId.put(option.optionId(), option));
        }
        return byId;
    }

    private static String authorization(String sessionToken) {
        return sessionToken == null ? null : BearerTokens.value(sessionToken);
    }
}
