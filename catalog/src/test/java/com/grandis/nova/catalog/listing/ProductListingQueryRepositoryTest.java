package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.MySqlContainerConfig;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.OffsetPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 접수 중 판정(CampaignWindow#acceptingAt)에 시각을 직접 넘겨 경계를 재고, MySQL general_log 로 실행 SQL 을 잡아 다른 서비스의 표를 읽지 않음을 못 박는다.
 * JdbcTemplate 의 SQL 은 Hibernate 인스펙터를 거치지 않으므로 서버 쪽 로그로 본다.
 */
@CatalogIntegrationTest
class ProductListingQueryRepositoryTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ProductListingQueryRepository repository;
    MySQLContainer mysql = MySqlContainerConfig.get();

    ShopFixtures fixtures;
    String tag;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        tag = "r" + ShopFixtures.unique().replace("-", "");
    }

    @Test
    @DisplayName("사전예약은 오픈 시각부터 보이고 마감 시각부터 숨는다 — 접수 단계(OPEN)와 같은 경계, 1ns 앞뒤로 목록 · 건수가 같이 바뀐다")
    void listedFromOpenUntilClose() {
        Instant opensAt = Instant.parse("2026-10-01T00:00:00Z");
        Instant closesAt = opensAt.plus(Duration.ofHours(1));
        UUID productId = fixtures.product(fixtures.category(), "PREORDER", "ACTIVE", "경계", tag);
        fixtures.registration(productId);
        Map<UUID, CampaignWindow> open = Map.of(productId, new CampaignWindow(opensAt, closesAt));
        ProductListFilter filter = new ProductListFilter(tag, null, null, null, null, null);

        for (Instant hidden : List.of(opensAt.minusNanos(1), closesAt)) {
            Set<UUID> accepting = CampaignWindow.acceptingAt(open, hidden);
            assertThat(ids(repository.findPage(filter, ProductSort.NEWEST, accepting, 0, 10).items())).as("%s", hidden).isEmpty();
            assertThat(repository.count(filter, accepting)).as("%s", hidden).isZero();
            assertThat(PreorderSaleStatus.of(opensAt, closesAt, hidden)).as("%s", hidden).isNotEqualTo(PreorderSaleStatus.OPEN);
        }
        for (Instant shown : List.of(opensAt, closesAt.minusNanos(1))) {
            Set<UUID> accepting = CampaignWindow.acceptingAt(open, shown);
            assertThat(ids(repository.findPage(filter, ProductSort.NEWEST, accepting, 0, 10).items())).as("%s", shown).containsExactly(productId);
            assertThat(repository.count(filter, accepting)).as("%s", shown).isEqualTo(1);
            assertThat(PreorderSaleStatus.of(opensAt, closesAt, shown)).isEqualTo(PreorderSaleStatus.OPEN);
        }
    }

    @Test
    @DisplayName("접수 중 id 는 사전예약 상품에만 먹는다 — 일반 상품은 판매 준비로만 보이고, 접수 중이 아닌 사전예약은 숨는다")
    void acceptingAppliesToPreorderOnly() {
        UUID category = fixtures.category();
        UUID unready = fixtures.product(category, "IN_STOCK", "ACTIVE", "준비 전 일반", tag);
        UUID preorder = fixtures.product(category, "PREORDER", "ACTIVE", "사전예약", tag);
        fixtures.registration(preorder);
        ProductListFilter filter = new ProductListFilter(tag, null, null, null, null, null);

        assertThat(repository.count(filter, List.of(unready))).as("일반 상품은 접수 중 id 로 보이지 않는다").isZero();
        assertThat(repository.count(filter, List.of())).as("접수 중이 아니면 숨는다").isZero();
        assertThat(ids(repository.findPage(filter, ProductSort.NEWEST, List.of(preorder), 0, 10).items())).as("대조군").containsExactly(preorder);
    }

    @Test
    @DisplayName("저장소는 preorder 회차 표(preorder_campaigns) · order 재고 표(option_inventories)를 읽지 않는다 — 회차는 preorder API, 품절 · 수량은 order 재고 API 다")
    void neverReadsForeignTables() throws Exception {
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
                repository.count(filter, List.of(productId));
                repository.findPage(filter, ProductSort.NEWEST, List.of(productId), 0, 10).items();
                repository.findListedCards(List.of(productId), List.of(productId));
                repository.findCards(List.of(productId), true);
                repository.countForAdmin(new AdminProductListFilter(tag, null, null, null));
                repository.findForAdmin(new AdminProductListFilter(tag, null, null, null), 0, 10);
                repository.findLinkable(null, 0, 10);
                repository.findExposure(productId);
                repository.findExposures(List.of(productId));
                repository.isStockReady(productId);
                repository.findActiveOptionIds(List.of(productId));
                repository.countGiftCandidates(tag);
                repository.findGiftCandidates(tag, 0, 10);
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

        // 양성 대조군 — 로그가 실제로 우리 조회를 잡았다(상품 표를 읽는 문장이 부른 메서드 수만큼은 있다)
        assertThat(statements.stream().filter(sql -> sql.contains("FROM products p")).count()).as("우리 조회가 잡혀야 한다").isGreaterThanOrEqualTo(10);
        assertThat(statements).as("preorder 회차 표는 읽지 않는다").noneMatch(sql -> sql.contains("preorder_campaigns"));
        assertThat(statements).as("order 재고 표는 읽지 않는다").noneMatch(sql -> sql.contains("option_inventories"));
    }

    @Test
    @DisplayName("한 쪽과 전체 건수는 한 문장 — 쪽을 나눠 이으면 한 번에 읽은 순서와 같고(정렬 셋), 범위 밖 쪽은 빈 목록에 total 을 그대로 준다")
    void pagesKeepOrderAndTotal() {
        UUID category = fixtures.category();
        // 등록 순서(최신이 마지막)와 최저가가 엇갈리게, 판매 중 옵션이 없는 상품(최저가 없음 — 가격순 둘 다 맨 뒤)도 하나
        List<UUID> byAge = new ArrayList<>();
        int[] prices = {3000, 1000, 5000, 2000, 0};
        for (int i = 0; i < prices.length; i++) {
            UUID productId = fixtures.product(category, "IN_STOCK", "ACTIVE", "쪽 " + i, tag);
            fixtures.option(productId, prices[i] == 0 ? "PAUSED" : "ACTIVE", new BigDecimal(prices[i] == 0 ? 9999 : prices[i]));
            fixtures.stockReady(productId);
            jdbcTemplate.update("UPDATE products SET created_at = ? WHERE id = UUID_TO_BIN(?)",
                    LocalDateTime.of(2026, 10, 1, 0, 0).plusMinutes(i), productId.toString());
            byAge.add(productId);
        }
        ProductListFilter filter = new ProductListFilter(tag, null, null, null, null, null);
        Map<ProductSort, List<UUID>> expected = Map.of(
                ProductSort.NEWEST, List.of(byAge.get(4), byAge.get(3), byAge.get(2), byAge.get(1), byAge.get(0)),
                ProductSort.PRICE_ASC, List.of(byAge.get(1), byAge.get(3), byAge.get(0), byAge.get(2), byAge.get(4)),
                ProductSort.PRICE_DESC, List.of(byAge.get(2), byAge.get(0), byAge.get(3), byAge.get(1), byAge.get(4)));

        for (ProductSort sort : ProductSort.values()) {
            OffsetPage<ProductListItem> whole = repository.findPage(filter, sort, List.of(), 0, 10);
            assertThat(ids(whole.items())).as("%s 한 번에", sort).containsExactlyElementsOf(expected.get(sort));
            assertThat(whole.total()).isEqualTo(5);
            List<UUID> joined = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                OffsetPage<ProductListItem> part = repository.findPage(filter, sort, List.of(), page, 2);
                assertThat(part.total()).as("%s %d쪽", sort, page).isEqualTo(5);
                joined.addAll(ids(part.items()));
            }
            assertThat(joined).as("%s 쪽을 이으면", sort).containsExactlyElementsOf(expected.get(sort));
            OffsetPage<ProductListItem> beyond = repository.findPage(filter, sort, List.of(), 3, 2);
            assertThat(beyond.items()).as("%s 범위 밖", sort).isEmpty();
            assertThat(beyond.total()).as("%s 범위 밖 total", sort).isEqualTo(5);
        }
        OffsetPage<ProductListItem> none = repository.findPage(new ProductListFilter(tag + "없음", null, null, null, null, null),
                ProductSort.NEWEST, List.of(), 0, 10);
        assertThat(none.items()).isEmpty();
        assertThat(none.total()).isZero();
    }

    @Test
    @DisplayName("통계가 실제와 달라도 목록 · 색상칩 · 관리자 목록 · 증정품 후보 · 판매 중 옵션 조회는 상품마다 옵션을 인덱스로 찾는다 — 옵션 표 전체를 훑지 않는다")
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
            // 목록(색상 필터) · 색상칩 · 관리자 목록(옵션 수) · 증정품 후보 · 판매 중 옵션 조회(품절 묻기 전) — 상품마다 옵션을 찾는 문장 전부
            List<UUID> productIds = jdbcTemplate.queryForList("SELECT BIN_TO_UUID(id) FROM products WHERE category_id = UUID_TO_BIN(?)", String.class,
                    category.toString()).stream().map(UUID::fromString).toList();
            List<String> statements = List.of(
                    capture(statement, "%min_price%NO_SEMIJOIN%LIMIT%", () -> repository.findPage(filter, ProductSort.PRICE_ASC, List.of(), 0, 20).items()),
                    capture(statement, "%sellable_colors%", () -> repository.findPage(filter, ProductSort.PRICE_ASC, List.of(), 0, 20).items()),
                    capture(statement, "%option_count%LIMIT%", () -> repository.findForAdmin(new AdminProductListFilter(tag, null, null, null), 0, 20)),
                    capture(statement, "%stock_ready_at IS NOT NULL%NO_SEMIJOIN%LIMIT%", () -> repository.findGiftCandidates(tag, 0, 20)),
                    capture(statement, "SELECT o.product_id, o.id FROM product_options o%", () -> repository.findActiveOptionIds(productIds)));
            try {
                // 틀린 통계 두 모양 — 구분값만 0(행 수는 실제) · 옵션 표는 행 수까지 0. 재고 표를 잇는 문장이 없어(order 재고 API) 재고 표 모양은 뺐다
                Map<String, List<String>> zeroRows = Map.of("구분값만", List.of(), "옵션 행 수까지", List.of("product_options"));
                for (Map.Entry<String, List<String>> shape : zeroRows.entrySet()) {
                    statement.execute("ANALYZE TABLE shop.products, shop.product_options");
                    for (String table : List.of("products", "product_options")) {
                        if (shape.getValue().contains(table)) {
                            statement.execute("UPDATE mysql.innodb_table_stats SET n_rows = 0, clustered_index_size = 1, sum_of_other_index_sizes = 1"
                                    + " WHERE database_name = 'shop' AND table_name = '" + table + "'");
                        }
                        statement.execute("UPDATE mysql.innodb_index_stats SET stat_value = 0 WHERE database_name = 'shop' AND table_name = '" + table
                                + "' AND stat_name LIKE 'n_diff_pfx%'");
                        statement.execute("FLUSH TABLE shop." + table);
                    }
                    // 대조군 — 힌트를 걷어낸 같은 문장은 이 통계에서 옵션 표를 훑는다(통계 조작이 먹혔다)
                    List<String> unhinted = new ArrayList<>();
                    for (String sql : statements) {
                        unhinted.addAll(optionAccess(statement, sql.replaceAll("/\\*\\+[^*]*\\*/", "").replaceAll("FORCE INDEX \\(\\w+\\)", "")));
                    }
                    assertThat(unhinted).as("%s — 힌트 없는 문장", shape.getKey()).anyMatch(access -> !access.endsWith(":ref") && !access.endsWith(":eq_ref"));
                    for (String sql : statements) {
                        // 판매 중 옵션 조회는 상품 id 여럿을 IN 으로 묻는다 — 같은 인덱스의 range 가 정상 계획이다(전체 스캔이 아니다)
                        boolean inList = sql.startsWith("SELECT o.product_id, o.id FROM product_options o");
                        assertThat(optionAccess(statement, sql)).as("%s — %s", shape.getKey(), sql.substring(0, 60)).isNotEmpty()
                                .allMatch(access -> access.endsWith(":ref") || access.endsWith(":eq_ref") || (inList && access.endsWith(":range")));
                    }
                }
            } finally {
                statement.execute("ANALYZE TABLE shop.products, shop.product_options");
            }
        }
    }

    /** 옵션 · 재고 표(별칭 o · ro · ri · inv · po · go · gi · sc · oc)의 접근 방식 — EXPLAIN FORMAT=JSON 의 access_type. */
    private static List<String> optionAccess(Statement statement, String sql) throws Exception {
        statement.execute("USE shop");
        List<String> access = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery("EXPLAIN FORMAT=JSON " + sql)) {
            rs.next();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"table_name\": \"(\\w+)\",\\s*\"access_type\": \"(\\w+)\"")
                    .matcher(rs.getString(1));
            while (m.find()) {
                if (m.group(1).matches("o|ro|ri|inv|po|go|gi|sc|oc")) {
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
        try (ResultSet rs = statement.executeQuery("SELECT argument FROM mysql.general_log WHERE command_type = 'Query'"
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
