package com.grandis.nova.catalog.query;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.product.SaleStatus;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.catalog.support.SqlHookInspector;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내부 조회의 registrationCompleted 는 판매 방식별 준비다 — 등록 이벤트를 받은 preorder · order 가 자기 표에 행을 만들었는가.
 * visible 은 칸 그대로라 준비와 따로 움직인다(관리자가 고른 값이 등록 때 바로 들어간다).
 */
@CatalogIntegrationTest
@TestPropertySource(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.grandis.nova.catalog.support.SqlHookInspector")
class ProductOptionsQueryServiceTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ProductOptionsQueryService service;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        SqlHookInspector.reset();
    }

    @AfterEach
    void tearDown() {
        SqlHookInspector.reset();
    }

    @Test
    @DisplayName("사전예약은 회차 행이 생기면 registrationCompleted=true — 회차 전에는 등록 기록이 있어도 false")
    void preorderCompletionFollowsCampaignRow() {
        UUID productId = fixtures.product("PREORDER", "ACTIVE");
        fixtures.registration(productId);
        assertThat(service.findProductOptions(productId).registrationCompleted()).isFalse();

        fixtures.campaign(productId, Instant.now().plus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(2)));
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
    @DisplayName("노출 칸을 읽은 뒤 커밋된 옵션 판매 중지는 이번 응답에 섞이지 않는다 — 접수 판정에 쓰는 노출 · 옵션 상태를 한 스냅샷(REPEATABLE READ)으로")
    void exposureAndOptionsReadOneSnapshot() {
        UUID productId = fixtures.product("IN_STOCK", "ACTIVE");
        UUID option = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
        boolean[] hookRan = {false};
        SqlHookInspector.before("product_options", () -> {
            commitOnAnotherConnection("UPDATE product_options SET status = 'PAUSED' WHERE id = UUID_TO_BIN('" + option + "')");
            hookRan[0] = true;
        });

        ProductOptionsView view = service.findProductOptions(productId);

        assertThat(hookRan[0]).as("훅이 돌았다").isTrue();
        assertThat(view.options().getFirst().status()).isEqualTo(SaleStatus.ACTIVE);
        assertThat(service.findProductOptions(productId).options().getFirst().status()).as("대조군 — 트랜잭션이 끝난 뒤에는 새 값")
                .isEqualTo(SaleStatus.PAUSED);
    }

    @Test
    @DisplayName("옵션 일괄 조회도 한 스냅샷이다 — 옵션을 읽은 뒤 커밋된 상품 제목(JPA 로 읽음) · 공개 여부(JDBC 로 읽음) 변경이 이번 응답에 섞이지 않는다")
    void optionLookupReadsOneSnapshot() {
        UUID productId = fixtures.product("IN_STOCK", "ACTIVE");
        UUID option = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
        boolean[] hookRan = {false};
        // 첫 읽기(옵션, product_options)가 스냅샷을 잡은 뒤 · 상품(products, JPA)을 읽기 직전 — 노출 칸은 그 뒤 JdbcTemplate 으로 읽는다.
        // 훅 뒤에 읽는 두 갈래(JPA 상품 · JDBC 노출)가 둘 다 옛 값이어야 같은 스냅샷이다
        SqlHookInspector.before(" from products ", () -> {
            commitOnAnotherConnection("UPDATE products SET title = '바뀐 이름', visible = 0 WHERE id = UUID_TO_BIN('" + productId + "')");
            hookRan[0] = true;
        });

        OptionLookupView view = service.findOptions(List.of(option)).getFirst();

        assertThat(hookRan[0]).as("훅이 돌았다").isTrue();
        // 두 갈래를 따로 판정한다 — 하나가 먼저 실패해도 다른 하나의 결과가 남게
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(view.productTitle()).as("상품(JPA)도 첫 읽기의 스냅샷").isEqualTo("Nova 1");
            softly.assertThat(view.visible()).as("노출 칸(JdbcTemplate)도 같은 스냅샷").isTrue();
        });
        OptionLookupView after = service.findOptions(List.of(option)).getFirst();
        assertThat(after.productTitle()).as("대조군 — 트랜잭션이 끝난 뒤에는 새 값").isEqualTo("바뀐 이름");
        assertThat(after.visible()).isFalse();
    }

    private void commitOnAnotherConnection(String sql) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> jdbcTemplate.update(sql)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            executor.shutdownNow();
        }
    }
}
