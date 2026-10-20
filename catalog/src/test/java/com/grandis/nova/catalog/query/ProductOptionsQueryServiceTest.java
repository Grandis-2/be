package com.grandis.nova.catalog.query;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.MySqlContainerConfig;
import com.grandis.nova.catalog.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내부 조회의 registrationCompleted 는 판매 방식별 준비다 — 등록 이벤트를 받은 preorder · order 가 자기 표에 행을 만들었는가.
 * visible 은 칸 그대로라 준비와 따로 움직인다(관리자가 고른 값이 등록 때 바로 들어간다).
 */
@CatalogIntegrationTest
class ProductOptionsQueryServiceTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ProductOptionsQueryService service;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    @DisplayName("사전예약은 회차 행이 없어도 registrationCompleted=true — 이 조회는 preorder 를 부르지 않는다(preorder 접수가 동기로 부른다)")
    void preorderIsAlwaysCompleted() {
        UUID productId = fixtures.product("PREORDER", "ACTIVE");
        fixtures.registration(productId);

        ProductOptionsView view = service.findProductOptions(productId);
        assertThat(view.registrationCompleted()).isTrue();
        assertThat(view.visible()).as("visible 은 칸 그대로(픽스처 기본값 1)").isTrue();
    }

    @Test
    @DisplayName("일반은 그 상품 옵션의 재고 행이 생기면 registrationCompleted=true — 사전예약 회차 행은 일반 상품의 준비가 아니다")
    void inStockCompletionFollowsInventoryRows() {
        UUID productId = fixtures.product("IN_STOCK", "ACTIVE");
        UUID option = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
        fixtures.registration(productId);
        // 판매 방식을 보고 판정하는지 — 일반 상품에 회차 행이 있어도 준비가 아니다(대조군)
        fixtures.campaign(productId, Instant.now().plus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(2)));
        assertThat(service.findProductOptions(productId).registrationCompleted()).isFalse();

        fixtures.inventory(option, 0, 0, 0);
        assertThat(service.findProductOptions(productId).registrationCompleted()).isTrue();
    }

    @Test
    @DisplayName("상품 · 옵션 조회는 노출 칸 · 상품 · 옵션을 한 문장으로 읽는다 — 트랜잭션 없이 한 시점(사이에 다른 커밋이 끼어들 틈이 없다)")
    void productOptionsAreOneStatement() throws Exception {
        UUID productId = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));

        Log log = capture("%FROM products p LEFT JOIN product_options%", () -> service.findProductOptions(productId));

        assertThat(log.readingCatalog()).as("모든 연결에서 상품 · 옵션 표를 읽은 문장 — 한 문장").singleElement()
                .satisfies(sql -> assertThat(sql).contains("FROM products p LEFT JOIN product_options"));
        assertThat(log.onMarkerConnection()).as("그 연결의 트랜잭션 준비 · 정리 문장").containsExactlyElementsOf(log.readingCatalog());
    }

    @Test
    @DisplayName("옵션 일괄 조회도 옵션 · 상품 · 노출 칸을 한 문장으로 읽는다 — 트랜잭션 없이 한 시점")
    void optionLookupIsOneStatement() throws Exception {
        UUID productId = fixtures.product("IN_STOCK", "ACTIVE");
        UUID first = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
        UUID second = fixtures.option(productId, "ACTIVE", new BigDecimal("2000"));

        Log log = capture("%FROM product_options o JOIN products p%", () -> service.findOptions(List.of(first, second)));

        assertThat(log.readingCatalog()).as("모든 연결에서 상품 · 옵션 표를 읽은 문장 — 한 문장").singleElement()
                .satisfies(sql -> assertThat(sql).contains("FROM product_options o JOIN products p"));
        assertThat(log.onMarkerConnection()).as("그 연결의 트랜잭션 준비 · 정리 문장").containsExactlyElementsOf(log.readingCatalog());
    }

    @Test
    @DisplayName("내부 상품 · 옵션 조회는 catalog 표(products · product_options)만 읽는다 — 회차 · 재고 표를 읽지 않는다")
    void readsOnlyCatalogTables() throws Exception {
        UUID productId = fixtures.product("IN_STOCK", "ACTIVE");
        UUID option = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));

        Log log = capture("%product_options%", () -> {
            service.findProductOptions(productId);
            service.findOptions(List.of(option));
        });

        assertThat(log.readingCatalog()).as("양성 대조군 — 두 조회가 잡혔다").hasSize(2);
        assertThat(log.all()).as("preorder 회차 표").noneMatch(sql -> sql.contains("preorder_campaigns"));
        assertThat(log.all()).as("order 재고 표").noneMatch(sql -> sql.contains("option_inventories"));
    }

    /**
     * 호출 구간 동안 앱 사용자(root 아님)가 보낸 문장(일반 로그). 드라이버가 새 연결에 보내는 설정 조회(주석으로 시작)는 뺀다.
     * {@link Log#readingCatalog} 는 연결과 상관없이 상품 · 옵션 표를 읽은 문장이고(두 문장으로 쪼개 다른 연결로 보내도 잡힌다),
     * {@link Log#onMarkerConnection} 은 표식 문장을 보낸 연결의 문장이다(트랜잭션을 열었다면 그 준비 · 정리 문장도 이 연결로 간다).
     */
    private static Log capture(String markerLike, ThrowingRunnable call) throws Exception {
        var mysql = MySqlContainerConfig.get();
        List<String> all = new ArrayList<>();
        List<String> onMarker = new ArrayList<>();
        try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement statement = root.createStatement()) {
            statement.execute("SET GLOBAL log_output = 'TABLE'");
            statement.execute("SET GLOBAL general_log = 'ON'");
            statement.execute("TRUNCATE TABLE mysql.general_log");
            try {
                call.run();
            } finally {
                statement.execute("SET GLOBAL general_log = 'OFF'");
            }
            try (ResultSet rs = statement.executeQuery("SELECT argument, thread_id IN (SELECT thread_id FROM mysql.general_log WHERE argument LIKE '"
                    + markerLike + "') AS on_marker FROM mysql.general_log WHERE command_type = 'Query' AND user_host NOT LIKE 'root%' ORDER BY event_time")) {
                while (rs.next()) {
                    String sql = new String(rs.getBytes(1), StandardCharsets.UTF_8);
                    if (sql.startsWith("/* mysql-connector")) {
                        continue;
                    }
                    all.add(sql);
                    if (rs.getBoolean(2)) {
                        onMarker.add(sql);
                    }
                }
            }
        }
        return new Log(all, onMarker);
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 상품 · 옵션 표 이름 — 단어로만 맞춘다(promotion_products 같은 다른 표 이름의 일부는 아니다). */
    private static final Pattern CATALOG_TABLE = Pattern.compile("\\b(products|product_options)\\b");

    record Log(List<String> all, List<String> onMarkerConnection) {
        List<String> readingCatalog() {
            return all.stream().filter(sql -> CATALOG_TABLE.matcher(sql).find()).toList();
        }
    }
}
