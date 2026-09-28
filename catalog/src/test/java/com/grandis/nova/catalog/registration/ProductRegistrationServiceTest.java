package com.grandis.nova.catalog.registration;

import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.catalog.support.SqlHookInspector;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** ② 에 넘길 계획이 요청에서 맞게 뽑히는지 — 컨트롤러는 계획을 안 쓰므로 서비스로 본다. */
@CatalogIntegrationTest
@TestPropertySource(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.grandis.nova.catalog.support.SqlHookInspector")
class ProductRegistrationServiceTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ProductRegistrationService service;

    ShopFixtures fixtures;
    Long categoryId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        categoryId = fixtures.category();
        SqlHookInspector.reset();
    }

    @AfterEach
    void tearDown() {
        SqlHookInspector.reset();
    }

    @Test
    @DisplayName("201 미리보기는 등록과 같은 트랜잭션에서 읽는다 — 미리보기 문장이 도는 시점에 등록 행은 아직 커밋 전이다")
    void previewIsReadInsideTheRegistrationTransaction() {
        String key = "k-" + ShopFixtures.unique();
        Integer[] committedRowsAtPreview = {null};
        // 미리보기의 상품 + 등록 JOIN 문장 직전에, 다른 커넥션에서 등록 행이 보이는지 센다
        SqlHookInspector.before("left join product_registrations", () -> committedRowsAtPreview[0] = countOnAnotherConnection(key));

        RegistrationOutcome created = service.register(key, inStockRequest());

        assertThat(committedRowsAtPreview[0]).as("훅이 돌았고, 그 시점에 등록 행은 커밋 전이었다").isZero();
        assertThat(created.preview().productId()).isEqualTo(created.plan().productId());
        assertThat(created.preview().visible()).isFalse();
        assertThat(countOnAnotherConnection(key)).as("대조군: 커밋 뒤에는 보인다").isEqualTo(1);
    }

    private Integer countOnAnotherConnection(String key) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(() -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM product_registrations WHERE idempotency_key = ?", Integer.class, key)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            executor.shutdownNow();
        }
    }

    private ProductRegistrationRequest inStockRequest() {
        return new ProductRegistrationRequest(categoryId, SaleMode.IN_STOCK, "케이블", null, null, true, new BigDecimal("9000"), null,
                null, List.of(new ProductRegistrationRequest.Combination(Map.of(), false, null, null, 3)), null, null, null);
    }

    @Test
    @DisplayName("일반 상품의 계획은 옵션 id 별 초기 재고를, 사전예약의 계획은 회차와 차수를 담는다")
    void planCarriesStepTwoInputs() {
        ProductRegistrationRequest inStock = new ProductRegistrationRequest(categoryId, SaleMode.IN_STOCK, "케이스", null, null,
                true, new BigDecimal("10000"), null,
                List.of(new ProductRegistrationRequest.OptionAxis("color", "색상",
                        List.of(new ProductRegistrationRequest.OptionValue("블랙", null), new ProductRegistrationRequest.OptionValue("화이트", null)))),
                List.of(new ProductRegistrationRequest.Combination(Map.of("color", "블랙"), false, null, null, 5),
                        new ProductRegistrationRequest.Combination(Map.of("color", "화이트"), false, null, null, 0)),
                null, null, null);
        RegistrationOutcome created = service.register("k-" + ShopFixtures.unique(), inStock);
        assertThat(created.kind()).isEqualTo(RegistrationOutcome.Kind.CREATED);
        Map<Long, Integer> stock = created.plan().stockByOptionId();
        assertThat(stock).hasSize(2).containsValues(5, 0);
        assertThat(stock.keySet()).allSatisfy(optionId -> assertThat(jdbcTemplate.queryForObject(
                "SELECT product_id FROM product_options WHERE id = ?", Long.class, optionId)).isEqualTo(created.plan().productId()));
        assertThat(created.plan().campaign()).isNull();
        assertThat(created.plan().shipmentBatches()).isEmpty();

        Instant opensAt = Instant.now().plus(Duration.ofHours(1));
        ProductRegistrationRequest preorder = new ProductRegistrationRequest(categoryId, SaleMode.PREORDER, "Nova", null, null,
                false, new BigDecimal("1000"), null, null, List.of(new ProductRegistrationRequest.Combination(Map.of(), false, null, null, null)),
                null, new ProductRegistrationRequest.Campaign(opensAt, opensAt.plus(Duration.ofDays(1))),
                List.of(new ProductRegistrationRequest.ShipmentBatch(1, 1L, null, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7))));
        RegistrationOutcome preorderCreated = service.register("k-" + ShopFixtures.unique(), preorder);
        assertThat(preorderCreated.plan().stockByOptionId()).isEmpty();
        assertThat(preorderCreated.plan().campaign().opensAt()).isEqualTo(opensAt);
        assertThat(preorderCreated.plan().shipmentBatches()).singleElement()
                .satisfies(batch -> {
                    assertThat(batch.batchNumber()).isEqualTo(1);
                    assertThat(batch.positionTo()).isNull();
                    assertThat(batch.estimatedShipEnd()).isEqualTo(LocalDate.of(2026, 11, 7));
                });

        // 재전송(미완료) 갈래에는 계획이 없다 — 조율 티켓이 원본에서 다시 만든다
        RegistrationOutcome again = service.register(preorderCreated.registration().idempotencyKey(), preorder);
        assertThat(again.kind()).isEqualTo(RegistrationOutcome.Kind.IN_PROGRESS);
        assertThat(again.plan()).isNull();
    }
}
