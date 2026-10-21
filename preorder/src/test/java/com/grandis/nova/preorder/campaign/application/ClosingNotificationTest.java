package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.campaign.domain.CloseNoticeStore;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회차가 닫히면 마감마다 한 번 PREORDER_CAMPAIGN_CLOSED 를 catalog-events 로 적는다(contracts/catalog-inbound-events.md).
 * 공유 DB 에는 다른 시험의 회차도 있어, 주기 작업은 한 번에 집는 수를 넉넉히 둔 인스턴스로 부른다.
 */
@PreorderIntegrationTest
class ClosingNotificationTest {

    static final String CLOSED = "PREORDER_CAMPAIGN_CLOSED";

    @Autowired
    CloseNoticeStore notices;

    @Autowired
    CloseNoticeNotifier notifier;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    Clock clock;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    CampaignClosingJob job;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        job = newJob(true);
    }

    @Test
    void 마감_시각이_지나면_건수와_함께_한_번만_알린다() {
        PreorderProduct product = fixtures.openPreorderProduct();
        AcceptFixtures accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        accepts.accept(fixtures.customer(), product);
        accepts.accept(fixtures.customer(), product);
        String canceled = AcceptFixtures.tokenOf(accepts.accept(fixtures.customer(), product));
        jdbcTemplate.update("UPDATE preorders SET status = 'CANCELED' WHERE preorder_token = ?", canceled);
        Instant closesAt = minutesAgo(5);
        close(product.productId(), closesAt);

        job.notifyClosedCampaigns();
        job.notifyClosedCampaigns();

        List<JsonNode> payloads = closedEvents(product.productId());
        assertThat(payloads).hasSize(1);
        JsonNode payload = payloads.getFirst();
        assertThat(payload.get("productId").asString()).isEqualTo(product.productId().toString());
        assertThat(Instant.parse(payload.get("closesAt").asString())).isEqualTo(closesAt);
        assertThat(payload.get("issuedCount").asLong()).as("취소 포함 발급 순번").isEqualTo(3);
        assertThat(payload.get("activeCount").asLong()).as("취소 제외").isEqualTo(2);
        assertThat(OutboundEventType.PREORDER_CAMPAIGN_CLOSED.destination()).isEqualTo("catalog-events");
    }

    @Test
    void 다시_열었다_닫으면_한_번_더_알린다() {
        UUID productId = fixtures.openPreorderProduct().productId();
        close(productId, minutesAgo(30));
        job.notifyClosedCampaigns();

        close(productId, minutesAgo(1));
        job.notifyClosedCampaigns();
        job.notifyClosedCampaigns();

        assertThat(closedEvents(productId)).hasSize(2);
    }

    @Test
    void 마감_뒤_공개가_바뀌어도_다시_알리지_않는다() {
        UUID productId = fixtures.openPreorderProduct().productId();
        close(productId, minutesAgo(5));
        job.notifyClosedCampaigns();

        fixtures.listing(productId, "아이폰 Duo", "ACTIVE", false);
        job.notifyClosedCampaigns();

        assertThat(closedEvents(productId)).hasSize(1);
    }

    @Test
    void 마감한_지_창을_넘었거나_아직_열린_회차는_알리지_않는다() {
        UUID expired = fixtures.openPreorderProduct().productId();
        close(expired, minutesAgo(90));
        UUID open = fixtures.openPreorderProduct().productId();

        job.notifyClosedCampaigns();

        assertThat(closedEvents(expired)).isEmpty();
        assertThat(closedEvents(open)).isEmpty();
    }

    @Test
    void 꺼져_있으면_주기_작업이_알리지_않는다() {
        UUID productId = fixtures.openPreorderProduct().productId();
        close(productId, minutesAgo(5));

        newJob(false).run();

        assertThat(closedEvents(productId)).isEmpty();
    }

    private CampaignClosingJob newJob(boolean enabled) {
        return new CampaignClosingJob(notices, notifier,
                new CloseNoticeProperties(enabled, Duration.ofSeconds(30), Duration.ofHours(1), 100_000), clock);
    }

    /** 일정 이벤트 · 캐시 없이 마감 시각만 옮긴다 — 작업은 DB 의 마감 시각만 본다. */
    private void close(UUID productId, Instant closesAt) {
        fixtures.schedule(productId, closesAt.minus(Duration.ofDays(1)), closesAt);
    }

    private Instant minutesAgo(long minutes) {
        return clock.instant().minus(Duration.ofMinutes(minutes)).truncatedTo(ChronoUnit.MICROS);
    }

    private List<JsonNode> closedEvents(UUID productId) {
        List<JsonNode> payloads = new ArrayList<>();
        jdbcTemplate.queryForList("""
                SELECT payload FROM preorder_outbox_events WHERE event_type = ? AND aggregate_id = ? ORDER BY id
                """, String.class, CLOSED, UuidBinary.toBytes(productId))
                .forEach(payload -> payloads.add(jsonMapper.readTree(payload)));
        return payloads;
    }
}
