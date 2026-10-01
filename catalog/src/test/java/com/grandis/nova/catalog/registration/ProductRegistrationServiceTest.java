package com.grandis.nova.catalog.registration;

import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.catalog.support.SqlHookInspector;
import com.grandis.nova.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ② 에 넘길 계획이 요청에서 맞게 뽑히고 등록 기록에 고정되는지 — 컨트롤러는 계획을 안 쓰므로 서비스로 본다. */
@CatalogIntegrationTest
@TestPropertySource(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.grandis.nova.catalog.support.SqlHookInspector")
class ProductRegistrationServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        // 계획은 마이크로초로 자른다. Instant.now() 는 Linux 에서 나노초, macOS 에서 마이크로초라(JDK 25 실측) 자른 값과 비교한다
        assertThat(preorderCreated.plan().campaign().opensAt()).isEqualTo(opensAt.truncatedTo(ChronoUnit.MICROS));
        assertThat(preorderCreated.plan().shipmentBatches()).singleElement()
                .satisfies(batch -> {
                    assertThat(batch.batchNumber()).isEqualTo(1);
                    assertThat(batch.positionTo()).isNull();
                    assertThat(batch.estimatedShipEnd()).isEqualTo(LocalDate.of(2026, 11, 7));
                });

    }

    @Test
    @DisplayName("재개(미완료 재전송)는 ① 에서 고정한 계획을 돌려준다 — 같은 키로 회차 · 차수 · 재고가 다른 본문이 와도 계획은 첫 등록 그대로다")
    void resumeUsesThePlanFixedAtStepOne() {
        Instant opensAt = Instant.now().plus(Duration.ofHours(1));
        ProductRegistrationRequest original = preorderRequest(opensAt,
                List.of(new ProductRegistrationRequest.ShipmentBatch(1, 1L, 100L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7)),
                        new ProductRegistrationRequest.ShipmentBatch(2, 101L, null, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14))));
        String key = "k-" + ShopFixtures.unique();
        RegistrationOutcome created = service.register(key, original);

        // 저장본: DB 의 JSON 이 ① 의 계획과 같다
        String stored = jdbcTemplate.queryForObject(
                "SELECT plan_payload FROM product_registrations WHERE product_id = ?", String.class, created.plan().productId());
        assertThat(JSON.readValue(stored, RegistrationPlan.class)).isEqualTo(created.plan());

        ProductRegistrationRequest different = preorderRequest(opensAt.plus(Duration.ofDays(2)),
                List.of(new ProductRegistrationRequest.ShipmentBatch(1, 1L, null, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2))));
        RegistrationOutcome resumed = service.register(key, different);
        assertThat(resumed.kind()).isEqualTo(RegistrationOutcome.Kind.IN_PROGRESS);
        assertThat(resumed.plan()).isEqualTo(created.plan());
        assertThat(resumed.plan().campaign().opensAt()).isEqualTo(opensAt.truncatedTo(ChronoUnit.MICROS));
        assertThat(resumed.plan().shipmentBatches()).hasSize(2);

        // 일반 상품: 재고가 다른 본문으로 다시 와도 옵션 id 별 재고는 첫 등록 그대로다
        String stockKey = "k-" + ShopFixtures.unique();
        RegistrationOutcome inStock = service.register(stockKey, inStockRequest());
        assertThat(inStock.plan().stockByOptionId()).hasSize(1).containsValue(3);
        RegistrationOutcome inStockAgain = service.register(stockKey, new ProductRegistrationRequest(categoryId, SaleMode.IN_STOCK,
                "케이블", null, null, true, new BigDecimal("9000"), null,
                null, List.of(new ProductRegistrationRequest.Combination(Map.of(), false, null, null, 99)), null, null, null));
        assertThat(inStockAgain.plan()).isEqualTo(inStock.plan());
    }

    @Test
    @DisplayName("완료된 등록의 재전송(REPLAYED)에는 계획이 없다 — 더 할 ② 가 없다")
    void completedReplayCarriesNoPlan() {
        String key = "k-" + ShopFixtures.unique();
        RegistrationOutcome created = service.register(key, inStockRequest());
        jdbcTemplate.update("UPDATE product_registrations SET completed_at = UTC_TIMESTAMP(6) WHERE product_id = ?",
                created.plan().productId());

        RegistrationOutcome replayed = service.register(key, inStockRequest());
        assertThat(replayed.kind()).isEqualTo(RegistrationOutcome.Kind.REPLAYED);
        assertThat(replayed.plan()).isNull();
    }

    @Test
    @DisplayName("픽스처로 만든 미완료 일반 상품 등록도 재개된다 — 계획은 그 시점 옵션마다 재고 0")
    void fixtureRegistrationResumesWithItsOptions() {
        Long productId = fixtures.product("IN_STOCK", "ACTIVE");
        Long optionId = fixtures.option(productId, "ACTIVE");
        String key = "k-" + ShopFixtures.unique();
        fixtures.registration(productId, key);

        RegistrationOutcome resumed = service.register(key, inStockRequest());
        assertThat(resumed.kind()).isEqualTo(RegistrationOutcome.Kind.IN_PROGRESS);
        assertThat(resumed.plan().productId()).isEqualTo(productId);
        assertThat(resumed.plan().stockByOptionId()).containsExactly(Map.entry(optionId, 0));
        assertThat(resumed.plan().campaign()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            계획 칸이 생기기 전의 행(JSON null)            | null
            모르는 칸이 있는 저장본(칸 이름이 바뀜)         | {"productId":%d,"stockByOptionId":{},"campaign":null,"shipmentBatches":[],"campaignV2":null}
            빠진 칸이 있는 저장본                          | {"productId":%d,"stockByOptionId":{},"shipmentBatches":[]}
            다른 상품의 계획                              | {"productId":-1,"stockByOptionId":{},"campaign":null,"shipmentBatches":[]}
            """)
    @DisplayName("저장된 계획이 없거나 못 읽는 미완료 등록은 재개하지 않고 409 REGISTRATION_BLOCKED(PLAN_UNAVAILABLE) — 202 로 빈 계획을 넘기지 않는다")
    void unavailablePlanBlocksTheResume(String caseName, String storedTemplate) {
        Long productId = fixtures.product("IN_STOCK", "ACTIVE");
        String key = "k-" + ShopFixtures.unique();
        fixtures.registration(productId, key);
        jdbcTemplate.update("UPDATE product_registrations SET plan_payload = CAST(? AS JSON) WHERE product_id = ?",
                storedTemplate.formatted(productId), productId);

        assertThatThrownBy(() -> service.register(key, inStockRequest()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(CatalogErrorCode.REGISTRATION_BLOCKED);
                    assertThat(e.details()).containsEntry("productId", productId)
                            .containsEntry("blockedReason", ProductRegistrationService.PLAN_UNAVAILABLE);
                });
        assertThat(jdbcTemplate.queryForObject("SELECT blocked_reason FROM product_registrations WHERE product_id = ?",
                String.class, productId)).as("기록하지 않는다 — 매번 같은 판정").isNull();
    }

    @Test
    @DisplayName("회차 시각은 마이크로초로 잘라 고정한다 — preorder 의 datetime(6) 과 같은 정밀도라 재개 대조에서 어긋나지 않는다")
    void campaignTimesAreFixedAtMicroseconds() {
        Instant opensAt = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS).plusNanos(123_456_789);
        String key = "k-" + ShopFixtures.unique();
        RegistrationOutcome created = service.register(key, preorderRequest(opensAt,
                List.of(new ProductRegistrationRequest.ShipmentBatch(1, 1L, null, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7)))));

        Instant micros = opensAt.truncatedTo(ChronoUnit.MICROS);
        assertThat(micros).isNotEqualTo(opensAt);
        assertThat(created.plan().campaign().opensAt()).isEqualTo(micros);
        assertThat(created.plan().campaign().closesAt()).isEqualTo(opensAt.plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS));
        assertThat(service.register(key, preorderRequest(opensAt, List.of())).plan()).isEqualTo(created.plan());
    }

    private ProductRegistrationRequest preorderRequest(Instant opensAt, List<ProductRegistrationRequest.ShipmentBatch> batches) {
        return new ProductRegistrationRequest(categoryId, SaleMode.PREORDER, "Nova", null, null,
                false, new BigDecimal("1000"), null, null, List.of(new ProductRegistrationRequest.Combination(Map.of(), false, null, null, null)),
                null, new ProductRegistrationRequest.Campaign(opensAt, opensAt.plus(Duration.ofDays(1))), batches);
    }
}
