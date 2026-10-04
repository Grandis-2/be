package com.grandis.nova.preorder.integration.catalog;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.web.client.InternalCallFailures;
import com.grandis.nova.preorder.integration.Dependencies;
import com.grandis.nova.preorder.integration.DependencyGuard;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * 접수에 쓸 상품 · 옵션 값. catalog 내부 API 로 상품 단위로 받아 메모리에 둔다.
 *
 * 접수 경로가 catalog 를 부르지 않게 하려는 캐시다. 오픈 직후 몰리는 접수(10초 5,000건)가
 * catalog 의 지연 · 장애에 묶이지 않고, catalog 가 preorder 만큼 늘어날 필요도 없다.
 * 사전예약 상품은 오픈 뒤 기준 정보가 동결되므로(ERD 결정 19 · 29 · 30) 캐시가 낡을 걱정이 작다.
 *
 * - 같은 상품을 동시에 처음 찾으면 한 번만 부른다(Caffeine 의 키별 단일 로딩). 오픈 순간의 몰림이 catalog 로 번지지 않는다.
 * - {@link #REFRESH_AFTER} 가 지나면 뒤에서 다시 받는다. 그동안은 가진 값을 쓰고, 다시 받기가 실패해도 가진 값을 유지한다.
 * - 판매 중지처럼 동결 뒤에도 바뀌는 것은 이벤트를 받아 {@link #evict} 한다.
 */
@Component
public class CatalogReader {

    static final String DEPENDENCY = Dependencies.CATALOG;

    static final Duration REFRESH_AFTER = Duration.ofMinutes(1);
    static final Duration EXPIRE_AFTER = Duration.ofMinutes(30);
    static final long MAXIMUM_PRODUCTS = 1_000;

    private final CatalogClient catalogClient;
    private final DependencyGuard dependencyGuard;
    private final LoadingCache<Long, Optional<ProductCatalog>> products;

    public CatalogReader(CatalogClient catalogClient, DependencyGuard dependencyGuard) {
        this.catalogClient = catalogClient;
        this.dependencyGuard = dependencyGuard;
        this.products = Caffeine.newBuilder()
                .refreshAfterWrite(REFRESH_AFTER)
                .expireAfterWrite(EXPIRE_AFTER)
                .maximumSize(MAXIMUM_PRODUCTS)
                .recordStats()
                .build(this::load);
    }

    /** 지표 등록용(CatalogCacheMetrics). */
    Cache<Long, Optional<ProductCatalog>> cache() {
        return products;
    }

    /**
     * 그 상품의 옵션 값. 상품이 없거나 그 상품의 옵션이 아니면 비어 있다.
     *
     * @throws BusinessException DEPENDENCY_UNAVAILABLE — 캐시에 없는데 catalog 가 응답하지 않을 때
     */
    public Optional<OptionSnapshot> findOption(Long productId, Long optionId) {
        return product(productId).flatMap(product -> product.snapshot(optionId));
    }

    /**
     * 상품과 옵션 전체. 상품이 없으면 비어 있다.
     *
     * @throws BusinessException DEPENDENCY_UNAVAILABLE — 캐시에 없는데 catalog 가 응답하지 않을 때
     */
    public Optional<ProductCatalog> findProduct(Long productId) {
        return product(productId);
    }

    /** 이벤트(판매 중지 등)로 값이 바뀐 상품을 비운다. 다음 조회가 catalog 에서 다시 받는다. */
    public void evict(Long productId) {
        products.invalidate(productId);
    }

    /**
     * 실패는 둘로 가른다.
     * - 404 외의 4xx · 읽을 수 없는 응답: 다시 불러도 같은 결과인 연동 오류(계약 불일치 등)다. 사용자 잘못이 아니므로
     *   catalog 의 상태를 그대로 돌려주지 않고 500 으로 둔다(응답 문구는 공통 처리기가 숨긴다).
     * - 그 밖(타임아웃 · 연결 실패 · 5xx): 일시 장애라 503 으로 다시 시도를 안내한다.
     */
    private Optional<ProductCatalog> product(Long productId) {
        try {
            return products.get(productId);
        } catch (CompletionException | RestClientException e) {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof HttpClientErrorException clientError) {
                throw InternalCallFailures.integrationError(DEPENDENCY, "productId=" + productId, clientError);
            }
            if (cause instanceof RestClientException restError && InternalCallFailures.isUnreadableResponse(restError)) {
                throw InternalCallFailures.unreadableResponse(DEPENDENCY, "productId=" + productId, restError);
            }
            throw dependencyGuard.callFailed(DEPENDENCY, "productId=" + productId, e);
        }
    }

    private Optional<ProductCatalog> load(Long productId) {
        try {
            return Optional.ofNullable(
                    dependencyGuard.call(DEPENDENCY, () -> catalogClient.getProduct(productId)).data());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
