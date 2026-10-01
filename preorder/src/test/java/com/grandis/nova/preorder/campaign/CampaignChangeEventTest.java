package com.grandis.nova.preorder.campaign;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.preorder.campaign.application.PreorderCampaignAdminService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 회차 일정이 바뀔 때마다 같은 트랜잭션에서 대기열용 회차 변경 이벤트가 적힌다. */
@PreorderIntegrationTest
class CampaignChangeEventTest {

    @Autowired
    PreorderCampaignAdminService service;

    @Autowired
    Campaigns campaigns;

    @Autowired
    CampaignRepublisher republisher;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    void 생성은_번호_1_로_변경은_번호를_올려_적고_같은_일정은_적지_않는다() {
        Long productId = fixtures.product("PREORDER", "ACTIVE");
        CatalogStubs.stubPreorderProduct(catalogClient, productId, CatalogStubs.activeOption(1L));
        Instant opensAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);

        service.upsertCampaign(productId, opensAt, opensAt.plusSeconds(3600));
        service.upsertCampaign(productId, opensAt, opensAt.plusSeconds(3600));
        Instant changed = opensAt.plusSeconds(600);
        service.upsertCampaign(productId, changed, changed.plusSeconds(3600));

        List<Map<String, Object>> events = events(productId);
        assertThat(events).extracting(event -> event.get("change")).containsExactly("CREATED", "RESCHEDULED");
        assertThat(events).extracting(event -> event.get("version")).containsExactly(1L, 2L);
        assertThat(events.get(1).get("opensAt")).isEqualTo(changed.toString());
        assertThat(events.get(1).get("closesAt")).isEqualTo(changed.plusSeconds(3600).toString());
        assertThat(scheduleVersion(productId)).isEqualTo(2);
    }

    @Test
    void 오픈_10분_안이라도_같은_일정으로_다시_저장하면_그대로_통과하고_적지_않는다() {
        Instant opensAt = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.SECONDS);
        PreorderProduct soon = fixtures.preorderProduct(opensAt, opensAt.plusSeconds(3600));
        CatalogStubs.stubPreorderProduct(catalogClient, soon.productId(), CatalogStubs.activeOption(soon.optionId()));

        service.upsertCampaign(soon.productId(), opensAt, opensAt.plusSeconds(3600));

        assertThat(events(soon.productId())).isEmpty();
        assertThat(scheduleVersion(soon.productId())).isZero();
    }

    @RepeatedTest(3)
    void 일정_변경과_판매_중지가_동시에_와도_번호는_1씩_오르고_이벤트마다_그때의_일정이_적힌다() throws Exception {
        Instant opensAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
        PreorderProduct product = fixtures.preorderProduct(opensAt, opensAt.plusSeconds(3600));
        Long productId = product.productId();
        CatalogStubs.stubPreorderProduct(catalogClient, productId, CatalogStubs.activeOption(product.optionId()));
        Instant closedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        List<Outcome<Object>> outcomes = Concurrently.run(5, i -> () -> {
            if (i == 0) {
                campaigns.closeNow(productId, closedAt);
                return null;
            }
            Instant changed = opensAt.plusSeconds(600L * i);
            return service.upsertCampaign(productId, changed, changed.plusSeconds(3600));
        });

        // 판매 중지 뒤에 잠금을 얻은 일정 변경은 오픈 뒤 변경 금지(409)로 실패한다
        assertThat(outcomes.getFirst().succeeded()).isTrue();
        assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded())
                .allMatch(outcome -> outcome.error() instanceof BusinessException);
        long changes = outcomes.stream().filter(Outcome::succeeded).count();
        List<Map<String, Object>> events = events(productId);
        assertThat(events).extracting(event -> event.get("version"))
                .containsExactlyElementsOf(LongStream.rangeClosed(1, changes).boxed().toList());
        assertThat(events).extracting(event -> event.get("change")).containsOnlyOnce("CLOSED");
        assertThat(scheduleVersion(productId)).isEqualTo(changes);
        Map<String, Object> last = events.getLast();
        assertThat(last.get("opensAt")).isEqualTo(utc(productId, "opens_at").toString());
        assertThat(last.get("closesAt")).isEqualTo(utc(productId, "closes_at").toString());
    }

    @Test
    void 판매_중지는_실제로_닫았을_때만_적는다() {
        Instant now = Instant.now();
        PreorderProduct upcoming = fixtures.preorderProduct(now.plusSeconds(3600), now.plusSeconds(7200));
        PreorderProduct ended = fixtures.preorderProduct(now.minusSeconds(7200), now.minusSeconds(3600));

        campaigns.closeNow(upcoming.productId(), now);
        campaigns.closeNow(upcoming.productId(), now.plusSeconds(1));
        campaigns.closeNow(ended.productId(), now);
        campaigns.closeNow(Long.MAX_VALUE, now);

        List<Map<String, Object>> events = events(upcoming.productId());
        assertThat(events).extracting(event -> event.get("change")).containsExactly("CLOSED");
        assertThat(events.getFirst().get("version")).as("번호를 매기기 전 회차(0)에서 오른다").isEqualTo(1L);
        assertThat(events(ended.productId())).isEmpty();
        assertThat(scheduleVersion(ended.productId())).isZero();
    }

    @Test
    void 전체_재발행은_마감_전_회차마다_지금_일정과_번호를_다시_적는다() {
        Instant now = Instant.now();
        PreorderProduct upcoming = fixtures.preorderProduct(now.plusSeconds(3600), now.plusSeconds(7200));
        PreorderProduct ended = fixtures.preorderProduct(now.minusSeconds(7200), now.minusSeconds(3600));
        campaigns.closeNow(upcoming.productId(), now.minusSeconds(1));
        PreorderProduct open = fixtures.preorderProduct(now.minusSeconds(60), now.plusSeconds(3600));

        assertThat(republisher.republishAll()).isPositive();
        assertThat(republisher.republishAll()).isPositive();

        assertThat(events(open.productId())).extracting(event -> event.get("change"))
                .containsExactly("RESYNC", "RESYNC");
        assertThat(events(open.productId())).extracting(event -> event.get("version")).containsOnly(0L);
        assertThat(events(upcoming.productId())).as("닫힌 회차는 다시 보내지 않는다")
                .extracting(event -> event.get("change")).containsExactly("CLOSED");
        assertThat(events(ended.productId())).isEmpty();
    }

    private List<Map<String, Object>> events(Long productId) {
        return jdbcTemplate.queryForList("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.change')) AS `change`,
                       CAST(JSON_EXTRACT(payload, '$.scheduleVersion') AS SIGNED) AS version,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.opensAt')) AS opensAt,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.closesAt')) AS closesAt
                  FROM outbox_events
                 WHERE event_type = 'PREORDER_CAMPAIGN_CHANGED' AND aggregate_type = 'PREORDER_CAMPAIGN'
                   AND aggregate_id = ?
                 ORDER BY id
                """, productId);
    }

    /** DB 는 UTC 벽시계 시각을 담는다. */
    private Instant utc(Long productId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM preorder_campaigns WHERE product_id = ?",
                LocalDateTime.class, productId).toInstant(ZoneOffset.UTC);
    }

    private long scheduleVersion(Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT schedule_version FROM preorder_campaigns WHERE product_id = ?", Long.class, productId);
    }
}
