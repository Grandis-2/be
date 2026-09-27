package com.grandis.nova.preorder.catalog;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.stereotype.Component;

/** catalog 상품 캐시의 적중 · 적재 · 퇴출 지표(cache.gets 등, cache=catalog.products). */
@Component
class CatalogCacheMetrics implements MeterBinder {

    static final String CACHE_NAME = "catalog.products";

    private final CatalogReader catalogReader;

    CatalogCacheMetrics(CatalogReader catalogReader) {
        this.catalogReader = catalogReader;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        CaffeineCacheMetrics.monitor(registry, catalogReader.cache(), CACHE_NAME);
    }
}
