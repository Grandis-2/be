package com.grandis.nova.preorder.integration.catalog;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.client.InternalCallFailures;
import com.grandis.nova.preorder.integration.Dependencies;
import com.grandis.nova.preorder.integration.DependencyGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.security.task.DelegatingSecurityContextTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 접수에 쓸 상품 · 옵션 값을 상품 단위로 캐시한다 — 오픈 순간의 몰림이 catalog 로 번지지 않게(같은 상품의 첫 조회는 한 번만 부른다).
 * 1분 지난 값은 그대로 돌려주고, 조회한 요청의 보안 맥락(토큰)으로 뒤에서 한 번 다시 받는다. 실패하면 가진 값, 30분 뒤 버린다.
 * 판매 중지처럼 동결 뒤에도 바뀌는 것은 이벤트로 {@link #evict} 한다.
 */
@Component
public class CatalogReader {

    static final String DEPENDENCY = Dependencies.CATALOG;
    static final String REFRESH_EXECUTOR = "catalogRefreshExecutor";

    static final Duration REFRESH_AFTER = Duration.ofMinutes(1);
    static final Duration EXPIRE_AFTER = Duration.ofMinutes(30);
    static final long MAXIMUM_PRODUCTS = 1_000;

    private static final Logger log = LoggerFactory.getLogger(CatalogReader.class);

    private final CatalogClient catalogClient;
    private final DependencyGuard dependencyGuard;
    private final TaskExecutor refreshExecutor;
    private final Clock clock;
    private final Cache<Long, Loaded> products;
    /** 다시 받는 중인 상품. 같은 상품을 동시에 여러 번 다시 받지 않는다. */
    private final Set<Long> refreshing = ConcurrentHashMap.newKeySet();

    public CatalogReader(CatalogClient catalogClient, DependencyGuard dependencyGuard,
                         @Qualifier(REFRESH_EXECUTOR) TaskExecutor refreshExecutor, Clock clock) {
        this.catalogClient = catalogClient;
        this.dependencyGuard = dependencyGuard;
        // 다시 받기는 다른 스레드에서 돈다. 맡긴 요청의 보안 맥락(토큰)을 옮겨 가야 catalog 가 받는다
        this.refreshExecutor = new DelegatingSecurityContextTaskExecutor(refreshExecutor);
        this.clock = clock;
        this.products = Caffeine.newBuilder()
                .expireAfterWrite(EXPIRE_AFTER)
                .maximumSize(MAXIMUM_PRODUCTS)
                .recordStats()
                .build();
    }

    /** 지표 등록용(CatalogCacheMetrics). */
    Cache<Long, Loaded> cache() {
        return products;
    }

    /**
     * 그 상품의 옵션 값. 상품이 없거나 그 상품의 옵션이 아니면 비어 있다.
     *
     * @throws BusinessException DEPENDENCY_UNAVAILABLE — 캐시에 없는데 catalog 가 응답하지 않을 때
     */
    public Optional<OptionSnapshot> findOption(Long productId, Long optionId) {
        return findProduct(productId).flatMap(product -> product.snapshot(optionId));
    }

    /**
     * 상품과 옵션 전체. 상품이 없으면 비어 있다.
     *
     * @throws BusinessException DEPENDENCY_UNAVAILABLE — 캐시에 없는데 catalog 가 응답하지 않을 때,
     *                           UNAUTHENTICATED — catalog 가 요청의 토큰을 받지 않을 때
     */
    public Optional<ProductCatalog> findProduct(Long productId) {
        Loaded loaded = get(productId);
        if (loaded.isOlderThan(REFRESH_AFTER, clock.instant())) {
            refreshInBackground(productId, loaded);
        }
        return loaded.product();
    }

    /** 이벤트(판매 중지 등)로 값이 바뀐 상품을 비운다. 다음 조회가 catalog 에서 다시 받는다. */
    public void evict(Long productId) {
        products.invalidate(productId);
    }

    /** 401 은 사용자 토큰 문제라 401, 404 외 4xx · 읽을 수 없는 응답은 연동 오류(500), 그 밖은 일시 장애(503). */
    private Loaded get(Long productId) {
        try {
            return products.get(productId, this::load);
        } catch (CompletionException | RestClientException e) {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof HttpClientErrorException.Unauthorized) {
                throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
            }
            if (cause instanceof HttpClientErrorException clientError) {
                throw InternalCallFailures.integrationError(DEPENDENCY, "productId=" + productId, clientError);
            }
            if (cause instanceof RestClientException restError && InternalCallFailures.isUnreadableResponse(restError)) {
                throw InternalCallFailures.unreadableResponse(DEPENDENCY, "productId=" + productId, restError);
            }
            throw dependencyGuard.callFailed(DEPENDENCY, "productId=" + productId, e);
        }
    }

    /**
     * 뒤에서 다시 받는다. 받는 사이 비워졌거나 다른 값으로 바뀌었으면 덮지 않는다 —
     * 판매 중지로 비운 상품에 옛 값을 다시 넣지 않게.
     */
    private void refreshInBackground(Long productId, Loaded stale) {
        if (!refreshing.add(productId)) {
            return;
        }
        try {
            refreshExecutor.execute(() -> {
                try {
                    products.asMap().replace(productId, stale, load(productId));
                } catch (RuntimeException e) {
                    log.warn("catalog 상품을 다시 받지 못해 가진 값을 쓴다 productId={}: {}", productId, e.toString());
                } finally {
                    refreshing.remove(productId);
                }
            });
        } catch (TaskRejectedException e) {
            refreshing.remove(productId);
        }
    }

    private Loaded load(Long productId) {
        Optional<ProductCatalog> product;
        try {
            product = Optional.ofNullable(dependencyGuard.call(DEPENDENCY,
                    () -> catalogClient.getProduct(productId)).data());
        } catch (HttpClientErrorException.NotFound e) {
            product = Optional.empty();
        }
        return new Loaded(product, clock.instant());
    }

    /** 받은 값과 받은 시각. 없는 상품(404)도 비어 있는 값으로 둔다. */
    record Loaded(Optional<ProductCatalog> product, Instant loadedAt) {

        boolean isOlderThan(Duration age, Instant now) {
            return !loadedAt.plus(age).isAfter(now);
        }
    }
}
