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

        List<String> statements = statements("%FROM products p LEFT JOIN product_options%", () -> service.findProductOptions(productId));

        assertThat(statements).as("노출 · 상품 · 옵션 한 문장, 트랜잭션 준비 · 정리 문장 없음").singleElement()
                .satisfies(sql -> assertThat(sql).contains("FROM products p LEFT JOIN product_options"));
    }

    @Test
    @DisplayName("옵션 일괄 조회도 옵션 · 상품 · 노출 칸을 한 문장으로 읽는다 — 트랜잭션 없이 한 시점")
    void optionLookupIsOneStatement() throws Exception {
        UUID productId = fixtures.product("IN_STOCK", "ACTIVE");
        UUID first = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
        UUID second = fixtures.option(productId, "ACTIVE", new BigDecimal("2000"));

        List<String> statements = statements("%FROM product_options o JOIN products p%", () -> service.findOptions(List.of(first, second)));

        assertThat(statements).as("옵션 · 상품 · 노출 한 문장, 트랜잭션 준비 · 정리 문장 없음").singleElement()
                .satisfies(sql -> assertThat(sql).contains("FROM product_options o JOIN products p"));
    }

    /**
     * 표식 문장을 보낸 연결이 이 호출에서 보낸 문장(일반 로그). 드라이버가 새 연결에 보내는 설정 조회(주석으로 시작)는 뺀다. 트랜잭션을 열었다면 그 준비 ·
     * 정리 문장도 같은 연결로 가 여기 잡힌다.
     */
    private static List<String> statements(String markerLike, Runnable call) throws Exception {
        var mysql = MySqlContainerConfig.get();
        List<String> statements = new ArrayList<>();
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
            try (ResultSet rs = statement.executeQuery("SELECT argument FROM mysql.general_log WHERE command_type = 'Query' AND thread_id IN"
                    + " (SELECT thread_id FROM mysql.general_log WHERE argument LIKE '" + markerLike + "') ORDER BY event_time")) {
                while (rs.next()) {
                    String sql = new String(rs.getBytes(1), StandardCharsets.UTF_8);
                    if (!sql.startsWith("/* mysql-connector")) {
                        statements.add(sql);
                    }
                }
            }
        }
        return statements;
    }
}
