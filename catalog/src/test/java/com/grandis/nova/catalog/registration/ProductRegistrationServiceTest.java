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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** 등록 트랜잭션이 판매 방식별 등록 이벤트를 아웃박스에 맞게 적는지 — 이벤트는 응답에 실리지 않으므로 서비스와 표로 본다. */
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
        // 미리보기가 등록 기록의 키를 읽는 문장 직전에, 다른 커넥션에서 등록 행이 보이는지 센다
        SqlHookInspector.before("idempotency_key from product_registrations", () -> committedRowsAtPreview[0] = countOnAnotherConnection(key));

        RegistrationOutcome created = service.register(key, inStockRequest());

        assertThat(committedRowsAtPreview[0]).as("훅이 돌았고, 그 시점에 등록 행은 커밋 전이었다").isZero();
        assertThat(created.preview().productId()).isEqualTo(created.registration().productId());
        assertThat(created.preview().visible()).as("관리자가 고른 공개 여부가 바로 담긴다").isTrue();
        assertThat(created.registration().completed()).as("이벤트는 커밋 뒤에 나가므로 아직 준비 전").isFalse();
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
    @DisplayName("일반 상품 등록은 order 로 갈 초기 재고 이벤트 하나를 적는다 — items 는 저장한 옵션 id 와 요청한 재고, 상품 id 는 봉투로만")
    void inStockRegistrationAppendsInitialStockEvent() {
        ProductRegistrationRequest inStock = new ProductRegistrationRequest(categoryId, SaleMode.IN_STOCK, "케이스", null, null,
                true, new BigDecimal("10000"), null,
                List.of(new ProductRegistrationRequest.OptionAxis("color", "색상",
                        List.of(new ProductRegistrationRequest.OptionValue("블랙", null), new ProductRegistrationRequest.OptionValue("화이트", null)))),
                List.of(new ProductRegistrationRequest.Combination(Map.of("color", "블랙"), false, null, null, 5),
                        new ProductRegistrationRequest.Combination(Map.of("color", "화이트"), false, null, null, 0)),
                null, null, null);
        RegistrationOutcome created = service.register("k-" + ShopFixtures.unique(), inStock);
        Long productId = created.registration().productId();

        Map<String, Object> event = singleEventOf(productId);
        assertThat(event).containsEntry("aggregate_type", "PRODUCT").containsEntry("event_type", "IN_STOCK_PRODUCT_REGISTERED");
        JsonNode payload = JSON.readTree((String) event.get("payload"));
        assertThat(payload.has("productId")).as("상품 id 는 aggregateId 로만").isFalse();
        Map<Long, Integer> stockByOption = new HashMap<>();
        payload.get("items").forEach(item -> stockByOption.put(item.get("optionId").asLong(), item.get("stockTotal").asInt()));
        Map<Long, String> skuByOption = new HashMap<>();
        jdbcTemplate.query("SELECT id, sku FROM product_options WHERE product_id = ?",
                rs -> { skuByOption.put(rs.getLong("id"), rs.getString("sku")); }, productId);
        assertThat(stockByOption).as("그 상품 옵션 전부").containsOnlyKeys(skuByOption.keySet()).containsValues(5, 0);
    }

    @Test
    @DisplayName("사전예약 등록은 preorder 로 갈 회차 · 차수 이벤트 하나를 적는다 — 회차 시각은 마이크로초로 자른다")
    void preorderRegistrationAppendsCampaignEvent() {
        Instant opensAt = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS).plusNanos(123_456_789);
        RegistrationOutcome created = service.register("k-" + ShopFixtures.unique(), preorderRequest(opensAt));
        Long productId = created.registration().productId();

        Map<String, Object> event = singleEventOf(productId);
        assertThat(event).containsEntry("aggregate_type", "PRODUCT").containsEntry("event_type", "PREORDER_PRODUCT_REGISTERED");
        JsonNode payload = JSON.readTree((String) event.get("payload"));
        assertThat(payload.has("productId")).isFalse();
        assertThat(Instant.parse(payload.get("campaign").get("opensAt").asString())).isEqualTo(opensAt.truncatedTo(ChronoUnit.MICROS));
        assertThat(Instant.parse(payload.get("campaign").get("closesAt").asString()))
                .isEqualTo(opensAt.plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS));
        assertThat(payload.get("shipmentBatches")).hasSize(2);
        JsonNode last = payload.get("shipmentBatches").get(1);
        assertThat(last.get("batchNumber").asInt()).isEqualTo(2);
        assertThat(last.get("positionFrom").asLong()).isEqualTo(101L);
        assertThat(last.get("positionTo").isNull()).isTrue();
        assertThat(last.get("estimatedShipEnd").asString()).isEqualTo("2026-11-14");
    }

    @Test
    @DisplayName("같은 키로 다시 오면 이벤트를 다시 적지 않는다 — 준비 전이면 IN_PROGRESS, preorder 회차 행이 생기면 REPLAYED")
    void replayDoesNotAppendAndFollowsReadiness() {
        String key = "k-" + ShopFixtures.unique();
        Instant opensAt = Instant.now().plus(Duration.ofHours(1));
        Long productId = service.register(key, preorderRequest(opensAt)).registration().productId();

        RegistrationOutcome pending = service.register(key, preorderRequest(opensAt));
        assertThat(pending.kind()).isEqualTo(RegistrationOutcome.Kind.IN_PROGRESS);
        assertThat(pending.registration().completed()).isFalse();

        fixtures.campaign(productId, opensAt, opensAt.plus(Duration.ofDays(1)));
        RegistrationOutcome done = service.register(key, preorderRequest(opensAt));
        assertThat(done.kind()).isEqualTo(RegistrationOutcome.Kind.REPLAYED);
        assertThat(done.registration().completed()).isTrue();
        assertThat(service.status(key).completed()).as("상태 조회도 같은 판정").isTrue();

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalog_outbox_events WHERE aggregate_id = ?",
                Integer.class, productId)).as("재전송은 이벤트를 다시 적지 않는다").isEqualTo(1);
    }

    private Map<String, Object> singleEventOf(Long productId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT aggregate_type, event_type, payload FROM catalog_outbox_events WHERE aggregate_id = ?", productId);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private ProductRegistrationRequest preorderRequest(Instant opensAt) {
        return new ProductRegistrationRequest(categoryId, SaleMode.PREORDER, "Nova", null, null,
                false, new BigDecimal("1000"), null, null, List.of(new ProductRegistrationRequest.Combination(Map.of(), false, null, null, null)),
                null, new ProductRegistrationRequest.Campaign(opensAt, opensAt.plus(Duration.ofDays(1))),
                List.of(new ProductRegistrationRequest.ShipmentBatch(1, 1L, 100L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7)),
                        new ProductRegistrationRequest.ShipmentBatch(2, 101L, null, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14))));
    }
}
