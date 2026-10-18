package com.grandis.nova.catalog.support;

import com.grandis.nova.catalog.integration.StockClient;
import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.web.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * order 재고 API(GET /internal/stocks) 자리 — 시험 DB 의 order 재고 행(option_inventories)을 order 와 같은 계산(총량 − 확보 − 판매, 0 아래는 0,
 * 행 없는 옵션은 빠짐)으로 돌려준다. 픽스처가 재고 행을 직접 넣는 기존 시험이 그대로 돈다. 실제 HTTP 계약은 StockReaderHttpTest 가 본다.
 * {@link #failing} 을 켜면 연결 실패처럼 던진다 — order 장애 갈래 시험용. 켠 시험은 반드시 끈다(컨텍스트를 같이 쓴다).
 */
@TestConfiguration(proxyBeanMethods = false)
public class OrderStocksTestConfig {

    public static volatile boolean failing;

    /** nova.test.fake-order-stocks=false 면 만들지 않는다 — 실제 HTTP 클라이언트를 보는 시험(StockReaderHttpTest)이 끈다. */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "nova.test.fake-order-stocks", havingValue = "true", matchIfMissing = true)
    StockClient databaseStockClient(JdbcTemplate jdbcTemplate) {
        return optionIds -> {
            if (failing) {
                throw new ResourceAccessException("order 재고 API 장애(시험)");
            }
            List<StockClient.Stock> items = new ArrayList<>();
            for (UUID optionId : new LinkedHashSet<>(optionIds)) {
                jdbcTemplate.query("SELECT GREATEST(stock_total - stock_reserved - stock_sold, 0) AS available FROM option_inventories WHERE option_id = ?",
                        rs -> {
                            items.add(new StockClient.Stock(optionId, rs.getInt("available")));
                        }, (Object) UuidBinary.toBytes(optionId));
            }
            return ApiResponse.ok(new StockClient.Stocks(items));
        };
    }
}
