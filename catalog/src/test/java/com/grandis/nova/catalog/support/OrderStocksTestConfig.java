package com.grandis.nova.catalog.support;

import com.grandis.nova.catalog.integration.StockClient;
import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.web.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * order 재고 API(GET /internal/stocks) 자리 — 시험 DB 의 order 재고 행(option_inventories)을 order 와 같은 계산(총량 − 확보 − 판매, 0 아래는 0,
 * 행 없는 옵션은 빠짐)으로 돌려준다. 픽스처가 재고 행을 직접 넣는 기존 시험이 그대로 돈다. 실제 HTTP 계약 · 장애 갈래는 StockReaderHttpTest 가 본다.
 * 실제 order 처럼 부른 쪽의 트랜잭션 밖에서 읽는다(새 트랜잭션) — 부른 쪽의 스냅샷 값이 아니라 그 시점에 커밋된 값이다. DB 트랜잭션 안에서
 * 불리면 {@link #CALLS_IN_TRANSACTION} 에 세고 던진다 — 모든 시험이 "행 잠금 · 연결을 쥔 채 order 를 부르지 않는다" 를 같이 지킨다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class OrderStocksTestConfig {

    public static final AtomicInteger CALLS_IN_TRANSACTION = new AtomicInteger();

    /** nova.test.fake-order-stocks=false 면 만들지 않는다 — 실제 HTTP 클라이언트를 보는 시험(StockReaderHttpTest)이 끈다. */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "nova.test.fake-order-stocks", havingValue = "true", matchIfMissing = true)
    StockClient databaseStockClient(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        TransactionTemplate outside = new TransactionTemplate(transactionManager);
        outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return optionIds -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                CALLS_IN_TRANSACTION.incrementAndGet();
                throw new IllegalStateException("DB 트랜잭션 안에서 order 재고를 물었다 — 연결 · 행 잠금을 쥔 채 HTTP 를 기다린다");
            }
            List<StockClient.Stock> items = new ArrayList<>();
            outside.executeWithoutResult(status -> {
                for (UUID optionId : new LinkedHashSet<>(optionIds)) {
                    jdbcTemplate.query("SELECT GREATEST(stock_total - stock_reserved - stock_sold, 0) AS available FROM option_inventories WHERE option_id = ?",
                            rs -> {
                                items.add(new StockClient.Stock(optionId, rs.getInt("available")));
                            }, (Object) UuidBinary.toBytes(optionId));
                }
            });
            return ApiResponse.ok(new StockClient.Stocks(items));
        };
    }
}
