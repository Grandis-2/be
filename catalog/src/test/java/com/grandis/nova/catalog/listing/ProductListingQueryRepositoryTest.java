package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장소에 시각을 직접 넘겨 경계를 재고, MySQL general_log 로 실행 SQL 을 잡아 비소유 표에 쓰기가 없음을 못 박는다.
 * JdbcTemplate 의 SQL 은 Hibernate 인스펙터를 거치지 않으므로 서버 쪽 로그로 본다.
 */
@CatalogIntegrationTest
class ProductListingQueryRepositoryTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ProductListingQueryRepository repository;
    @Autowired MySQLContainer mysql;

    ShopFixtures fixtures;
    String tag;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        tag = "r" + ShopFixtures.unique().replace("-", "");
    }

    @Test
    @DisplayName("마감 + 120시간 직전에는 보이고, 그 시각부터는 숨는다")
    void hideBoundaryIsExactlyOneHundredTwentyHours() {
        Instant closesAt = Instant.parse("2026-10-01T00:00:00Z");
        Long productId = fixtures.product(fixtures.category(), "PREORDER", "ACTIVE", "경계", tag);
        fixtures.completeRegistration(productId);
        fixtures.campaign(productId, closesAt.minusSeconds(3600), closesAt);
        // 상수를 참조하면 상수가 틀려도 시험이 같이 움직인다 — 확정값 120시간을 여기 박는다
        Instant boundary = closesAt.plus(Duration.ofHours(120));
        ProductListFilter filter = new ProductListFilter(tag, null, null, null, null);

        assertThat(ids(repository.find(filter, boundary.minusNanos(1_000), 0, 10))).containsExactly(productId);
        assertThat(repository.count(filter, boundary.minusNanos(1_000))).isEqualTo(1);
        assertThat(ids(repository.find(filter, boundary, 0, 10))).isEmpty();
        assertThat(repository.count(filter, boundary)).isZero();
    }

    @Test
    @DisplayName("목록 · 검색은 다른 서비스의 표(preorder_campaigns · option_inventories)를 읽기만 한다")
    void readsButNeverWritesForeignTables() throws Exception {
        Long productId = fixtures.product(fixtures.category(), "IN_STOCK", "ACTIVE", "읽기만", tag);
        fixtures.completeRegistration(productId);
        Long axis = fixtures.axis(productId, "color", 0);
        Long black = fixtures.value(axis, "블랙", 0);
        Long option = fixtures.option(productId, "ACTIVE", new java.math.BigDecimal("1000"));
        fixtures.selection(productId, option, axis, black);
        fixtures.inventory(option, 1, 0, 0);
        ProductListFilter filter = new ProductListFilter(tag, null, null, List.of("블랙"), List.of());

        List<String> statements;
        try (Connection root = rootConnection(); Statement statement = root.createStatement()) {
            statement.execute("SET GLOBAL log_output = 'TABLE'");
            statement.execute("SET GLOBAL general_log = 'ON'");
            statement.execute("TRUNCATE TABLE mysql.general_log");
            try {
                repository.count(filter, Instant.now());
                repository.find(filter, Instant.now(), 0, 10);
            } finally {
                statement.execute("SET GLOBAL general_log = 'OFF'");
            }
            statements = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery("SELECT argument FROM mysql.general_log WHERE command_type = 'Query'")) {
                while (rs.next()) {
                    statements.add(new String(rs.getBytes(1), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }

        List<String> foreign = statements.stream()
                .filter(sql -> sql.contains("preorder_campaigns") || sql.contains("option_inventories"))
                .toList();
        // 양성 대조군 — 로그가 실제로 우리 조회를 잡았다
        assertThat(foreign).as("두 표를 읽는 SELECT 가 잡혀야 한다").isNotEmpty();
        assertThat(foreign).allSatisfy(sql -> assertThat(sql.strip().toUpperCase(Locale.ROOT)).startsWith("SELECT"));
        assertThat(statements).noneMatch(sql -> {
            String upper = sql.strip().toUpperCase(Locale.ROOT);
            return (upper.startsWith("INSERT") || upper.startsWith("UPDATE") || upper.startsWith("DELETE"))
                    && (sql.contains("preorder_campaigns") || sql.contains("option_inventories"));
        });
    }

    private Connection rootConnection() throws Exception {
        return DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
    }

    private static List<Long> ids(List<ProductListItem> items) {
        return items.stream().map(ProductListItem::productId).toList();
    }
}
