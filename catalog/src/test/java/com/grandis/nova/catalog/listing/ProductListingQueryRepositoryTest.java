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
import java.util.UUID;

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
    @DisplayName("사전예약은 오픈 시각부터 보이고 마감 시각부터 숨는다 — 접수 단계(OPEN)와 같은 경계, 1µs 앞뒤로 목록 · 건수가 같이 바뀐다")
    void listedFromOpenUntilClose() {
        Instant opensAt = Instant.parse("2026-10-01T00:00:00Z");
        Instant closesAt = opensAt.plus(Duration.ofHours(1));
        UUID productId = fixtures.product(fixtures.category(), "PREORDER", "ACTIVE", "경계", tag);
        fixtures.registration(productId);
        fixtures.campaign(productId, opensAt, closesAt);
        ProductListFilter filter = new ProductListFilter(tag, null, null, null, null, null);
        Duration micro = Duration.ofNanos(1_000);   // datetime(6) 의 한 칸

        for (Instant hidden : List.of(opensAt.minus(micro), closesAt)) {
            assertThat(ids(repository.find(filter, ProductSort.NEWEST, hidden, 0, 10))).as("%s", hidden).isEmpty();
            assertThat(repository.count(filter, hidden)).as("%s", hidden).isZero();
            assertThat(PreorderSaleStatus.of(opensAt, closesAt, hidden)).as("%s", hidden).isNotEqualTo(PreorderSaleStatus.OPEN);
        }
        // µs 아래가 붙은 시각도 접수 단계와 같은 쪽 — 마감 1ns 전은 OPEN 이고 목록에도 있다
        Instant lastNano = closesAt.minusNanos(1);
        assertThat(PreorderSaleStatus.of(opensAt, closesAt, lastNano)).isEqualTo(PreorderSaleStatus.OPEN);
        assertThat(repository.count(filter, lastNano)).isEqualTo(1);
        for (Instant shown : List.of(opensAt, closesAt.minus(micro))) {
            assertThat(ids(repository.find(filter, ProductSort.NEWEST, shown, 0, 10))).as("%s", shown).containsExactly(productId);
            assertThat(repository.count(filter, shown)).as("%s", shown).isEqualTo(1);
            assertThat(PreorderSaleStatus.of(opensAt, closesAt, shown)).isEqualTo(PreorderSaleStatus.OPEN);
        }
    }

    @Test
    @DisplayName("목록 · 검색은 다른 서비스의 표(preorder_campaigns · option_inventories)를 읽기만 한다")
    void readsButNeverWritesForeignTables() throws Exception {
        UUID productId = fixtures.product(fixtures.category(), "IN_STOCK", "ACTIVE", "읽기만", tag);
        fixtures.registration(productId);
        ShopFixtures.AxisRef axis = fixtures.axis(productId, "color", 0);
        String black = fixtures.value(axis, "블랙", 0);
        UUID option = fixtures.option(productId, "ACTIVE", new java.math.BigDecimal("1000"));
        fixtures.selection(productId, option, axis, black);
        fixtures.inventory(option, 1, 0, 0);
        ProductListFilter filter = new ProductListFilter(tag, null, null, null, List.of("블랙"), List.of());

        List<String> statements;
        try (Connection root = rootConnection(); Statement statement = root.createStatement()) {
            statement.execute("SET GLOBAL log_output = 'TABLE'");
            statement.execute("SET GLOBAL general_log = 'ON'");
            statement.execute("TRUNCATE TABLE mysql.general_log");
            try {
                repository.count(filter, Instant.now());
                repository.find(filter, ProductSort.NEWEST, Instant.now(), 0, 10);
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

    @Test
    @DisplayName("통계가 실제와 달라도 목록 · 증정품 후보는 상품마다 옵션을 인덱스로 찾고 옵션부터 재고를 잇는다 — 옵션 · 재고 표 전체를 훑지 않는다")
    void planDoesNotDependOnStatistics() throws Exception {
        UUID category = fixtures.category();
        for (int i = 0; i < 30; i++) {
            UUID productId = fixtures.product(category, "IN_STOCK", "ACTIVE", "통계 " + i, tag);
            fixtures.registration(productId);
            for (int k = 0; k < 2; k++) {
                UUID option = fixtures.optionWithAttributes(productId, "ACTIVE", new java.math.BigDecimal(1000 + i), "o" + k, "{\"color\": \"화이트\"}");
                fixtures.inventory(option, 5, 0, 0);
            }
        }
        ProductListFilter filter = new ProductListFilter(null, category, null, null, List.of("화이트"), List.of());
        try (Connection root = rootConnection(); Statement statement = root.createStatement()) {
            String list = capture(statement, "%sold_out%", () -> repository.find(filter, ProductSort.PRICE_ASC, Instant.now(), 0, 20));
            String gifts = capture(statement, "%option_inventories gi%", () -> repository.findGiftCandidates(tag, 0, 20));
            List<String> tables = List.of("products", "product_options", "option_inventories");
            try {
                // 틀린 통계 두 모양 — 구분값만 0(행 수는 실제) · 옵션 · 재고 표는 행 수까지 0. 옵티마이저가 고르는 나쁜 계획이 서로 달라 힌트마다 한쪽이 잡는다
                for (boolean rowsToo : new boolean[] {false, true}) {
                    statement.execute("ANALYZE TABLE shop.products, shop.product_options, shop.option_inventories");
                    for (String table : tables) {
                        if (rowsToo && !table.equals("products")) {
                            statement.execute("UPDATE mysql.innodb_table_stats SET n_rows = 0, clustered_index_size = 1, sum_of_other_index_sizes = 1"
                                    + " WHERE database_name = 'shop' AND table_name = '" + table + "'");
                        }
                        statement.execute("UPDATE mysql.innodb_index_stats SET stat_value = 0 WHERE database_name = 'shop' AND table_name = '" + table
                                + "' AND stat_name LIKE 'n_diff_pfx%'");
                        statement.execute("FLUSH TABLE shop." + table);
                    }
                    for (String sql : List.of(list, gifts)) {
                        assertThat(optionAccess(statement, sql)).as("옵션 · 재고 행 수까지 0: %s — %s", rowsToo, sql.substring(0, 60)).isNotEmpty()
                                .allMatch(access -> access.endsWith(":ref") || access.endsWith(":eq_ref"));
                    }
                }
            } finally {
                statement.execute("ANALYZE TABLE shop.products, shop.product_options, shop.option_inventories");
            }
        }
    }

    /** 옵션 · 재고 표(별칭 o · ro · ri · inv · po · go · gi)의 접근 방식 — EXPLAIN FORMAT=JSON 의 access_type. */
    private static List<String> optionAccess(Statement statement, String sql) throws Exception {
        statement.execute("USE shop");
        List<String> access = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery("EXPLAIN FORMAT=JSON " + sql)) {
            rs.next();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"table_name\": \"(\\w+)\",\\s*\"access_type\": \"(\\w+)\"")
                    .matcher(rs.getString(1));
            while (m.find()) {
                if (m.group(1).matches("o|ro|ri|inv|po|go|gi")) {
                    access.add(m.group(1) + ":" + m.group(2));
                }
            }
        }
        return access;
    }

    /** 이 호출이 서버에 보낸 문장 중 like 에 맞는 것(값이 들어간 채)을 일반 로그로 받아 온다. */
    private String capture(Statement statement, String like, Runnable call) throws Exception {
        statement.execute("SET GLOBAL log_output = 'TABLE'");
        statement.execute("SET GLOBAL general_log = 'ON'");
        statement.execute("TRUNCATE TABLE mysql.general_log");
        try {
            call.run();
        } finally {
            statement.execute("SET GLOBAL general_log = 'OFF'");
        }
        try (ResultSet rs = statement.executeQuery("SELECT argument FROM mysql.general_log WHERE command_type = 'Query' AND argument LIKE '%LIMIT%'"
                + " AND argument LIKE '" + like + "'")) {
            assertThat(rs.next()).as("문장이 로그에 있다: %s", like).isTrue();
            return new String(rs.getBytes(1), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private Connection rootConnection() throws Exception {
        return DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
    }

    private static List<UUID> ids(List<ProductListItem> items) {
        return items.stream().map(ProductListItem::productId).toList();
    }
}
