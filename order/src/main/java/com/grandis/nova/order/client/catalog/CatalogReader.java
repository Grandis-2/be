package com.grandis.nova.order.client.catalog;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.client.InternalCallFailures;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 요청 스레드에서 부르므로 서킷 브레이커 → 동시 호출 상한을 거친다(설정 기본값은 OrderDefaults). 회로가 열렸거나 상한이 차면
 * 부르지 않고 503 이다 — catalog 가 느려져도 장바구니와 무관한 주문 API 의 요청 스레드가 남는다.
 *
 * 사용자의 액세스 토큰을 Authorization: Bearer 로 그대로 싣는다. 토큰은 로그 · 예외 메시지에 싣지 않는다.
 */
@Component
public class CatalogReader {

    private static final Logger log = LoggerFactory.getLogger(CatalogReader.class);

    /** 장애 대응 설정(resilience4j.*.instances.catalog)과 같은 이름. */
    public static final String DEPENDENCY = "catalog";
    static final String OPERATION = "getOptions";

    private final CatalogClient catalogClient;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    public CatalogReader(CatalogClient catalogClient, CircuitBreakerRegistry circuitBreakers, BulkheadRegistry bulkheads) {
        this.catalogClient = catalogClient;
        this.circuitBreaker = circuitBreakers.circuitBreaker(DEPENDENCY);
        this.bulkhead = bulkheads.bulkhead(DEPENDENCY);
    }

    /**
     * 옵션 id → 사실. 없는 옵션은 빠진다(호출자가 "판매 종료" 로 판정). 빈 묶음이면 부르지 않는다.
     *
     * @param sessionToken 사용자가 보낸 액세스 토큰 원문(접두어 없음). null 이면 싣지 않는다(catalog 가 401)
     * @throws BusinessException     UNAUTHENTICATED — catalog 가 토큰을 거절(401). DEPENDENCY_UNAVAILABLE — 타임아웃 · 연결 실패 · 5xx,
     *                               회로 열림 · 동시 호출 상한
     * @throws IllegalStateException 연동 오류(500) — 그 밖의 4xx(경로 없음 · 계약 어긋남), 읽을 수 없는 응답, 판정 칸이 빠진 옵션 · 묻지 않은 옵션
     */
    public Map<UUID, CatalogOption> find(Collection<UUID> optionIds, String sessionToken) {
        Map<UUID, CatalogOption> byId = new LinkedHashMap<>();
        if (optionIds.isEmpty()) {
            return byId;
        }
        CatalogOptions options;
        try {
            options = CircuitBreaker.decorateSupplier(circuitBreaker, Bulkhead.decorateSupplier(bulkhead,
                    () -> catalogClient.getOptions(optionIds, authorization(sessionToken)).data())).get();
        } catch (CallNotPermittedException e) {
            log.warn("{} 회로가 열려 호출하지 않는다", DEPENDENCY);
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (BulkheadFullException e) {
            log.warn("{} 동시 호출 상한이 찼다", DEPENDENCY);
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
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
            options.items().forEach(option -> byId.put(requireReadable(optionIds, option), option));
        }
        return byId;
    }

    /**
     * 판정 · 금액에 쓰는 칸이 다 있고, 물은 옵션인가. 빠진 칸을 기본값으로 읽으면 가격 0 · 보증가 0 · 잘못된 거절 사유가 되고,
     * 묻지 않은 옵션을 받으면 물은 옵션이 판매 종료로 보인다. 다시 물어도 같으므로 연동 오류(500)다.
     *
     * @return 그 옵션의 id
     */
    private static UUID requireReadable(Collection<UUID> requested, CatalogOption option) {
        boolean complete = option.optionId() != null && option.price() != null && option.saleMode() != null
                && option.productStatus() != null && option.optionStatus() != null && option.visible() != null
                && option.registrationCompleted() != null && option.warranty() != null
                && (!option.warranty().offered() || option.warranty().surcharge() != null);
        if (!complete || !requested.contains(option.optionId())) {
            log.error("{} 연동 오류 {} 읽을 수 없는 옵션 optionId={} complete={}", DEPENDENCY, OPERATION, option.optionId(), complete);
            throw new IllegalStateException(DEPENDENCY + " 연동 오류: 읽을 수 없는 옵션");
        }
        return option.optionId();
    }

    private static String authorization(String sessionToken) {
        return sessionToken == null ? null : BearerTokens.value(sessionToken);
    }
}
