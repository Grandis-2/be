package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.MySqlContainerConfig;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.OffsetPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 관리자 목록의 건수와 목록은 두 문장이다. 사이에 다른 커넥션의 INSERT 가 커밋돼도 둘은 같은 스냅샷이어야 한다(REPEATABLE READ).
 * 저장소는 JdbcTemplate 이라 Hibernate 인스펙터로는 틈을 못 만든다 — 저장소 빈을 spy 로 감싸 count 뒤에 끼워 넣는다.
 */
@CatalogIntegrationTest
class ProductListingServiceTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ProductListingService service;
    @MockitoSpyBean ProductListingQueryRepository repository;

    @Test
    @DisplayName("관리자 목록 — 건수와 목록 사이에 커밋된 새 상품은 이번 응답에 섞이지 않는다")
    void adminListCountsAndItemsComeFromOneSnapshot() throws Exception {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        String tag = "t" + ShopFixtures.unique().replace("-", "");
        UUID categoryId = fixtures.category();
        UUID existing = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "먼저", tag);
        UUID[] inserted = {null};
        doAnswer(invocation -> {
            Object total = invocation.callRealMethod();
            inserted[0] = onAnotherConnection(() -> fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "사이에", tag));
            return total;
        }).when(repository).countForAdmin(any());

        OffsetPage<AdminProductListItem> page = service.listForAdmin(new AdminProductListFilter(tag, null, null, null), 0, 20);

        assertThat(inserted[0]).as("count 뒤에 다른 커넥션이 정말 끼어들었다").isNotNull();
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(AdminProductListItem::productId).containsExactly(existing);
        // 트랜잭션이 끝난 뒤에는 둘 다 보인다 — 대조군
        assertThat(service.listForAdmin(new AdminProductListFilter(tag, null, null, null), 0, 20).total()).isEqualTo(2);
    }

    @Test
    @DisplayName("회원 목록 — 건수 · 목록을 한 문장으로 읽고 트랜잭션을 열지 않는다(격리 · 읽기 전용 전환 문장이 없다)")
    void memberListReadsCountAndItemsInOneStatementWithoutTransaction() throws Exception {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        String tag = "m" + ShopFixtures.unique().replace("-", "");
        UUID productId = fixtures.product(fixtures.category(), "IN_STOCK", "ACTIVE", "한 문장", tag);
        fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
        fixtures.stockReady(productId);
        var mysql = MySqlContainerConfig.get();
        List<String> statements = new ArrayList<>();
        OffsetPage<ProductListItem> page;
        try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement statement = root.createStatement()) {
            statement.execute("SET GLOBAL log_output = 'TABLE'");
            statement.execute("SET GLOBAL general_log = 'ON'");
            statement.execute("TRUNCATE TABLE mysql.general_log");
            try {
                page = service.list(new ProductListFilter(tag, null, SaleMode.IN_STOCK, null, null, null), ProductSort.NEWEST, 0, 20);
            } finally {
                statement.execute("SET GLOBAL general_log = 'OFF'");
            }
            // 목록 문장을 보낸 연결의 문장만 — 같은 서버를 쓰는 다른 작업(아웃박스 릴레이 등)의 문장을 섞지 않는다. 트랜잭션을 열었다면 준비 문장도 이 연결로 간다
            try (ResultSet rs = statement.executeQuery("SELECT argument FROM mysql.general_log WHERE command_type = 'Query' AND thread_id IN"
                    + " (SELECT thread_id FROM mysql.general_log WHERE argument LIKE '%LEFT JOIN LATERAL%') ORDER BY event_time")) {
                while (rs.next()) {
                    statements.add(new String(rs.getBytes(1), StandardCharsets.UTF_8));
                }
            }
        }

        assertThat(page.items()).extracting(ProductListItem::productId).containsExactly(productId);
        assertThat(page.total()).isEqualTo(1);
        // 양성 대조군 — 로그가 이 조회를 잡았다
        assertThat(statements).as("건수 · 목록 한 문장").filteredOn(sql -> sql.contains("LEFT JOIN LATERAL")).hasSize(1);
        assertThat(statements).as("건수를 따로 세지 않는다").noneMatch(sql -> sql.startsWith("SELECT COUNT(*) FROM products"));
        assertThat(statements).as("트랜잭션 준비 · 정리 문장").noneMatch(sql -> sql.contains("TRANSACTION ISOLATION") || sql.contains("READ ONLY"));
    }

    private static <T> T onAnotherConnection(java.util.concurrent.Callable<T> work) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(work).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            executor.shutdownNow();
        }
    }
}
