package com.grandis.nova.catalog.event;

import com.grandis.nova.catalog.cache.CatalogCacheInvalidation;
import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.message.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * 일반 판매 상품의 판매 준비(contracts/order-catalog-events.md 의 IN_STOCK_PRODUCT_READY). order 가 그 상품 옵션의 재고 행을 만들 때마다 보낸다.
 * products.stock_ready_at 이 비어 있을 때만 채운다 — 준비는 되돌아가지 않으므로 같은 메시지가 두 번 오거나 순서가 뒤집혀도 결과가 같다.
 * 상품 행이 없으면 계약 위반이라(order 는 catalog 표에서 상품을 확인한 트랜잭션에서 보낸다) 실패로 올린다 — 재시도 뒤 DLQ 로 간다.
 * 판매 방식은 다시 확인하지 않는다(order 는 일반 판매 상품에만 재고 행을 만든다). 지운 상품이어도 표시만 남긴다 — 목록은 deleted_at 으로 거른다.
 *
 * 상품 엔티티를 거치지 않는 쓰기라 JPA 콜백이 캐시를 비우지 않는다 — 채웠을 때 커밋 뒤 직접 비운다(목록 · 랭킹 메모리, 상세 세대).
 */
@Component
class InStockProductReadyHandler implements CatalogEventHandler {

    static final String EVENT_TYPE = "IN_STOCK_PRODUCT_READY";
    static final String AGGREGATE_TYPE = "PRODUCT";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final CatalogCacheInvalidation invalidation;

    InStockProductReadyHandler(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, CatalogCacheInvalidation invalidation) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.invalidation = invalidation;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    @Override
    public void handle(UUID productId, EventEnvelope envelope) {
        transaction.executeWithoutResult(status -> {
            byte[] id = UuidBinary.toBytes(productId);
            int marked = jdbc.update("UPDATE products SET stock_ready_at = UTC_TIMESTAMP(6) WHERE id = ? AND stock_ready_at IS NULL", (Object) id);
            if (marked == 1) {
                invalidation.productsChanged();
                return;
            }
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id = ?", Integer.class, (Object) id);
            if (exists == null || exists == 0) {
                throw new IllegalStateException("판매 준비 이벤트의 상품이 없다: productId=" + productId);
            }
        });
    }
}
