package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.preorder.campaign.application.OpenAlertNotifier.AbandonReason;
import com.grandis.nova.preorder.campaign.application.OpenAlertNotifier.Chunk;
import com.grandis.nova.preorder.campaign.domain.OpenAlertDispatchStore;
import com.grandis.nova.preorder.campaign.domain.OpenAlertStore;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 회차가 열리면 표시하고, 신청자에게 1,000명씩 묶어 NOTIFICATION_REQUESTED 를 묶음마다 따로인 트랜잭션으로 적는다
 * (contracts/notification-events.md "오픈 알림"). 공유 DB 에는 다른 시험의 회차도 있어, 주기 작업은 한 번에 집는 수를 넉넉히 둔 인스턴스로 부른다.
 */
@PreorderIntegrationTest
class OpeningNotificationTest {

    static final String NOTIFICATION = "NOTIFICATION_REQUESTED";

    @Autowired
    PreorderCampaignRepository campaigns;

    @Autowired
    OpenAlertNotifier notifier;

    @Autowired
    OpenAlertStore alerts;

    @Autowired
    OpenAlertDispatchStore dispatches;

    @Autowired
    OutboxWriter outboxWriter;

    @Autowired
    MeterRegistry appMeterRegistry;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    Clock clock;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    SimpleMeterRegistry meterRegistry;
    CampaignOpeningJob job;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        meterRegistry = new SimpleMeterRegistry();
        job = newJob();
    }

    @Test
    void 연_회차는_신청자를_1000명씩_묶어_한_번만_알리고_표시한다() {
        UUID productId = openedMinutesAgo(5, "아이폰 Duo", "ACTIVE", true);
        List<UUID> subscribers = subscribe(productId, 2_500);

        job.notifyOpenedCampaigns();
        job.notifyOpenedCampaigns();

        List<JsonNode> payloads = notifications(productId);
        assertThat(payloads).extracting(payload -> payload.get("recipientIds").size()).containsExactly(1000, 1000, 500);
        assertThat(recipientsOf(payloads)).as("신청자 모두 한 번씩").hasSize(2_500).containsExactlyInAnyOrderElementsOf(subscribers);
        assertThat(payloads.getFirst().get("kind").asString()).isEqualTo("PREORDER_OPENED");
        assertThat(payloads.getFirst().get("title").asString()).isEqualTo("사전예약 오픈");
        assertThat(payloads.getFirst().get("body").asString()).isEqualTo("알림 신청하신 아이폰 Duo 사전예약이 시작됐어요.");
        assertThat(payloads.getFirst().get("target")).isEqualTo(jsonMapper.readTree(
                "{\"type\":\"PRODUCT\",\"id\":\"" + productId + "\"}"));
        assertThat(fixtures.count("""
                SELECT COUNT(*) FROM preorder_outbox_events
                 WHERE event_type = ? AND aggregate_id = ? AND aggregate_type = 'PREORDER_CAMPAIGN'
                """, NOTIFICATION, UuidBinary.toBytes(productId))).as("원인은 회차(상품 id)").isEqualTo(3);
        assertThat(notifiedAt(productId)).isNotNull();
        assertThat(dispatchCompletedAt(productId)).isNotNull();
    }

    @Test
    void 신청자가_없으면_표시만_하고_발송을_끝낸다() {
        UUID productId = openedMinutesAgo(5, "아이폰 Duo", "ACTIVE", true);

        job.notifyOpenedCampaigns();

        assertThat(notifications(productId)).isEmpty();
        assertThat(notifiedAt(productId)).isNotNull();
        assertThat(dispatchCompletedAt(productId)).isNotNull();
    }

    /** 발송 중에 죽어 커서만 옮긴 채 남은 진행 행을 다음 주기가 이어 간다. 보낸 묶음은 다시 보내지 않는다. */
    @Test
    void 중간에_멈춘_발송은_다음_주기에_커서부터_이어_간다() {
        UUID productId = openedMinutesAgo(5, "아이폰 Duo", "ACTIVE", true);
        List<UUID> subscribers = subscribe(productId, 2_500);
        Instant now = clock.instant();
        assertThat(notifier.markOpened(productId, now, now.minus(Duration.ofHours(1)))).isTrue();
        assertThat(notifier.sendNextChunk(productId)).isEqualTo(new Chunk(1, false, false));
        assertThat(dispatchCompletedAt(productId)).isNull();

        newJob().notifyOpenedCampaigns();

        List<JsonNode> payloads = notifications(productId);
        assertThat(payloads).extracting(payload -> payload.get("recipientIds").size()).containsExactly(1000, 1000, 500);
        assertThat(recipientsOf(payloads)).hasSize(2_500).containsExactlyInAnyOrderElementsOf(subscribers);
        assertThat(dispatchCompletedAt(productId)).isNotNull();
    }

    @Test
    void 비공개_판매_중지면_이유를_세고_보내지도_표시하지도_않고_창_안에_다시_공개되면_그때_보낸다() {
        UUID hidden = openedMinutesAgo(5, "숨김", "ACTIVE", false);
        UUID paused = openedMinutesAgo(5, "중지", "PAUSED", true);
        subscribe(hidden, 1);
        subscribe(paused, 1);

        job.notifyOpenedCampaigns();

        assertThat(notifications(hidden)).isEmpty();
        assertThat(notifications(paused)).isEmpty();
        assertThat(notifiedAt(hidden)).isNull();
        assertThat(notifiedAt(paused)).isNull();
        assertThat(skipped("HIDDEN")).as("다른 시험의 회차도 셀 수 있다").isGreaterThanOrEqualTo(1);
        assertThat(skipped("PAUSED")).isGreaterThanOrEqualTo(1);

        fixtures.listing(hidden, "숨김", "ACTIVE", true);
        job.notifyOpenedCampaigns();

        assertThat(notifications(hidden)).hasSize(1);
        assertThat(notifiedAt(hidden)).isNotNull();
        assertThat(notifiedAt(paused)).isNull();
    }

    @Test
    void 이미_보냈거나_연_지_1시간이_넘었거나_마감됐거나_아직_안_열린_회차는_건드리지_않는다() {
        UUID notified = openedMinutesAgo(5, "보냄", "ACTIVE", true);
        jdbcTemplate.update("UPDATE preorder_campaigns SET open_notified_at = UTC_TIMESTAMP(6) WHERE product_id = ?",
                (Object) UuidBinary.toBytes(notified));
        UUID stale = openedMinutesAgo(61, "오래됨", "ACTIVE", true);
        Instant now = Instant.now();
        UUID closed = fixtures.preorderProduct(now.minusSeconds(300), now.minusSeconds(1)).productId();
        UUID upcoming = fixtures.preorderProduct(now.plusSeconds(3600), now.plusSeconds(7200)).productId();
        for (UUID productId : List.of(notified, stale, closed, upcoming)) {
            subscribe(productId, 1);
        }

        job.notifyOpenedCampaigns();

        for (UUID productId : List.of(notified, stale, closed, upcoming)) {
            assertThat(notifications(productId)).as(productId.toString()).isEmpty();
        }
        assertThat(notifiedAt(stale)).isNull();
        assertThat(notifiedAt(closed)).isNull();
        assertThat(notifiedAt(upcoming)).isNull();
    }

    /** 상품명 사본을 받기 전 회차(판매 상태도 모름)는 막지 않고 상품명 없는 문구로 보낸다. */
    @Test
    void 상품_사본이_없는_회차는_상품명_없는_문구로_보낸다() {
        Instant now = Instant.now();
        UUID productId = fixtures.preorderProduct(now.minusSeconds(300), now.plusSeconds(3600)).productId();
        subscribe(productId, 1);

        job.notifyOpenedCampaigns();

        assertThat(notifications(productId)).singleElement()
                .satisfies(payload -> assertThat(payload.get("body").asString()).isEqualTo("알림 신청하신 사전예약이 시작됐어요."));
    }

    @Test
    void 여러_인스턴스가_같은_회차를_동시에_돌려도_표시는_한_번_묶음마다_한_번씩_보낸다() throws Exception {
        UUID productId = openedMinutesAgo(5, "아이폰 Duo", "ACTIVE", true);
        List<UUID> subscribers = subscribe(productId, 2_500);

        List<Outcome<Integer>> outcomes = Concurrently.run(4, i -> {
            CampaignOpeningJob instance = newJob();
            return instance::notifyOpenedCampaigns;
        });

        assertThat(outcomes).allMatch(Outcome::succeeded);
        List<JsonNode> payloads = notifications(productId);
        assertThat(payloads).hasSize(3);
        assertThat(recipientsOf(payloads)).hasSize(2_500).containsExactlyInAnyOrderElementsOf(subscribers);
    }

    /**
     * 묶음 트랜잭션은 진행 행만 잠근다 — 묶음이 열려 있는 동안 접수처럼 회차 행을 FOR UPDATE 로 잠가도 기다리지 않고,
     * 같은 회차의 다음 묶음은 진행 행에서 기다린다. 묶음을 바깥 트랜잭션에 묶어 커밋 전의 잠금을 그대로 둔다.
     */
    @Test
    void 발송_중에도_회차_행은_잠기지_않고_같은_회차의_묶음끼리만_줄_선다() throws Exception {
        UUID productId = openedMinutesAgo(5, "아이폰 Duo", "ACTIVE", true);
        subscribe(productId, 1_500);
        Instant now = clock.instant();
        assertThat(notifier.markOpened(productId, now, now.minus(Duration.ofHours(1)))).isTrue();
        CountDownLatch chunkOpen = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> firstChunk = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(status -> {
            notifier.sendNextChunk(productId);
            chunkOpen.countDown();
            await(release);
        }));
        assertThat(chunkOpen.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Boolean> accept = CompletableFuture.supplyAsync(() -> transactionTemplate.execute(status ->
                campaigns.findForUpdate(productId).isPresent()));
        CompletableFuture<Chunk> nextChunk = CompletableFuture.supplyAsync(() -> notifier.sendNextChunk(productId));

        assertThat(accept.get(5, TimeUnit.SECONDS)).as("회차 행 잠금이 묶음을 기다리지 않는다").isTrue();
        Awaitility.await("다음 묶음은 진행 행에서 기다린다").during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5))
                .until(() -> !nextChunk.isDone());
        release.countDown();

        firstChunk.get(10, TimeUnit.SECONDS);
        assertThat(nextChunk.get(10, TimeUnit.SECONDS)).isEqualTo(new Chunk(1, true, true));
        assertThat(notifications(productId)).extracting(payload -> payload.get("recipientIds").size())
                .containsExactly(1000, 500);
    }

    /**
     * 오픈 경계에서 신청이 회차를 공유 잠금한 채 진행 중이면 표시가 그 커밋을 기다리고, 표시 뒤의 발송이 그 회원을 읽는다.
     * 신청 쪽 판정은 이 시험의 관심이 아니라 잠금과 넣기만 흉내 낸다(표시 뒤 신청의 거절은 OpenAlertApiTest).
     */
    @Test
    void 경계에서_진행_중인_신청은_표시가_기다렸다가_함께_보낸다() throws Exception {
        UUID productId = openedMinutesAgo(1, "아이폰 Duo", "ACTIVE", true);
        UUID lateSubscriber = UUID.randomUUID();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> subscribing = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(status -> {
            campaigns.findForShare(productId).orElseThrow();
            alerts.subscribe(productId, lateSubscriber, Instant.now());
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        Instant now = clock.instant();

        CompletableFuture<Boolean> marking = CompletableFuture.supplyAsync(() ->
                notifier.markOpened(productId, now, now.minus(Duration.ofHours(1))));
        Awaitility.await("신청이 커밋할 때까지 표시가 기다린다").during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5))
                .until(() -> !marking.isDone());
        release.countDown();

        subscribing.get(10, TimeUnit.SECONDS);
        assertThat(marking.get(10, TimeUnit.SECONDS)).isTrue();
        job.notifyOpenedCampaigns();
        assertThat(notifications(productId)).singleElement()
                .satisfies(payload -> assertThat(payload.get("recipientIds").get(0).asString())
                        .isEqualTo(lateSubscriber.toString()));
    }

    /** 비공개 · 판매 중지 회차가 오래된 오픈이어도 한 주기 상한을 차지하지 않는다. 앞선 시험이 남긴 창 안의 회차는 먼저 비운다. */
    @Test
    void 비공개_판매_중지_회차는_상한을_차지하지_않는다() {
        job.notifyOpenedCampaigns();
        openedMinutesAgo(45, "숨김", "ACTIVE", false);
        openedMinutesAgo(45, "중지", "PAUSED", true);
        UUID visible = openedMinutesAgo(40, "아이폰 Duo", "ACTIVE", true);
        subscribe(visible, 1);

        assertThat(newJob(1).notifyOpenedCampaigns()).isEqualTo(1);

        assertThat(notifiedAt(visible)).isNotNull();
        assertThat(notifications(visible)).hasSize(1);
        assertThat(skipped("HIDDEN")).isGreaterThanOrEqualTo(1);
        assertThat(skipped("PAUSED")).isGreaterThanOrEqualTo(1);
    }

    /** 묶음 하나를 보낸 뒤 회차가 더 보낼 상태가 아니게 되면, 이어 가는 묶음은 보내지 않고 발송을 끝낸다. */
    @ParameterizedTest
    @EnumSource(AbandonReason.class)
    void 이어_보내다_회차가_보낼_상태가_아니면_남은_신청자에게_보내지_않고_끝낸다(AbandonReason reason) {
        UUID productId = openedMinutesAgo(5, "아이폰 Duo", "ACTIVE", true);
        subscribe(productId, 1_500);
        Instant now = clock.instant();
        assertThat(notifier.markOpened(productId, now, now.minus(Duration.ofHours(1)))).isTrue();
        assertThat(notifier.sendNextChunk(productId)).isEqualTo(new Chunk(1, false, false));
        double abandoned = abandoned(reason);

        switch (reason) {
            case CLOSED -> updateCampaign("closes_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND", productId);
            case WINDOW_PASSED -> updateCampaign("opens_at = UTC_TIMESTAMP(6) - INTERVAL 61 MINUTE", productId);
            case HIDDEN -> fixtures.listing(productId, "아이폰 Duo", "ACTIVE", false);
            case PAUSED -> fixtures.listing(productId, "아이폰 Duo", "PAUSED", true);
        }

        assertThat(notifier.sendNextChunk(productId)).isEqualTo(Chunk.NONE);
        assertThat(notifications(productId)).as("첫 묶음만").hasSize(1);
        assertThat(dispatchCompletedAt(productId)).isNotNull();
        assertThat(abandoned(reason)).isEqualTo(abandoned + 1);
        assertThat(notifier.sendNextChunk(productId)).as("끝낸 발송은 다시 세지 않는다").isEqualTo(Chunk.NONE);
        assertThat(abandoned(reason)).isEqualTo(abandoned + 1);
    }

    /**
     * 묶음 트랜잭션에서 아웃박스 적기가 실패하면 적은 행과 커서가 함께 롤백되고, 다음 주기가 그 묶음부터 다시 보낸다.
     * 실패하는 쓰기를 끼우려 묶음을 직접 만들고 트랜잭션은 시험이 연다(빈의 @Transactional 과 같은 경계).
     */
    @Test
    void 묶음에서_아웃박스_적기가_실패하면_커서도_그대로고_다음_주기에_그_묶음부터_보낸다() {
        UUID productId = openedMinutesAgo(5, "아이폰 Duo", "ACTIVE", true);
        List<UUID> subscribers = subscribe(productId, 1_500);
        Instant now = clock.instant();
        assertThat(notifier.markOpened(productId, now, now.minus(Duration.ofHours(1)))).isTrue();
        OutboxWriter failingWriter = mock(OutboxWriter.class);
        doAnswer(invocation -> {
            outboxWriter.append(invocation.getArgument(0));
            throw new IllegalStateException("아웃박스 적은 뒤 실패");
        }).when(failingWriter).append(any());
        OpenAlertNotifier failing = new OpenAlertNotifier(campaigns, alerts, dispatches, failingWriter, properties(100_000),
                appMeterRegistry, clock);

        assertThatThrownBy(() -> transactionTemplate.execute(status -> failing.sendNextChunk(productId)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(notifications(productId)).as("적은 행도 롤백").isEmpty();
        assertThat(dispatchCursor(productId)).isNull();
        assertThat(dispatchCompletedAt(productId)).isNull();

        newJob().notifyOpenedCampaigns();

        List<JsonNode> payloads = notifications(productId);
        assertThat(payloads).extracting(payload -> payload.get("recipientIds").size()).containsExactly(1000, 500);
        assertThat(recipientsOf(payloads)).hasSize(1_500).containsExactlyInAnyOrderElementsOf(subscribers);
    }

    private CampaignOpeningJob newJob() {
        return newJob(100_000);
    }

    private CampaignOpeningJob newJob(int batchSize) {
        return new CampaignOpeningJob(campaigns, dispatches, notifier, properties(batchSize), meterRegistry, clock);
    }

    private static OpenAlertProperties properties(int batchSize) {
        return new OpenAlertProperties(Duration.ofSeconds(30), Duration.ofHours(1), batchSize);
    }

    private double skipped(String reason) {
        return meterRegistry.counter(CampaignOpeningJob.SKIPPED_METRIC, "reason", reason).count();
    }

    /** 묶음은 컨텍스트의 notifier 가 컨텍스트 레지스트리에 센다. */
    private double abandoned(AbandonReason reason) {
        return appMeterRegistry.counter(OpenAlertNotifier.ABANDONED_METRIC, "reason", reason.name()).count();
    }

    private void updateCampaign(String set, UUID productId) {
        jdbcTemplate.update("UPDATE preorder_campaigns SET " + set + " WHERE product_id = ?",
                (Object) UuidBinary.toBytes(productId));
    }

    private UUID openedMinutesAgo(long minutes, String title, String status, boolean visible) {
        Instant now = Instant.now();
        UUID productId = fixtures.preorderProduct(now.minus(Duration.ofMinutes(minutes)), now.plus(Duration.ofHours(2)))
                .productId();
        fixtures.listing(productId, title, status, visible);
        return productId;
    }

    /** 회원 행은 넣지 않는다 — 신청은 회원 표에 외래 키가 없다. */
    private List<UUID> subscribe(UUID productId, int count) {
        List<UUID> customers = IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList();
        jdbcTemplate.batchUpdate("""
                INSERT INTO preorder_open_alerts (product_id, customer_id, created_at) VALUES (?, ?, UTC_TIMESTAMP(6))
                """, customers.stream()
                .map(customer -> new Object[] {UuidBinary.toBytes(productId), UuidBinary.toBytes(customer)})
                .toList());
        return customers;
    }

    private LocalDateTime notifiedAt(UUID productId) {
        return jdbcTemplate.queryForObject("SELECT open_notified_at FROM preorder_campaigns WHERE product_id = ?",
                LocalDateTime.class, (Object) UuidBinary.toBytes(productId));
    }

    private LocalDateTime dispatchCompletedAt(UUID productId) {
        return jdbcTemplate.queryForObject("SELECT completed_at FROM preorder_open_alert_dispatches WHERE product_id = ?",
                LocalDateTime.class, (Object) UuidBinary.toBytes(productId));
    }

    private byte[] dispatchCursor(UUID productId) {
        return jdbcTemplate.queryForObject("SELECT last_customer_id FROM preorder_open_alert_dispatches WHERE product_id = ?",
                byte[].class, (Object) UuidBinary.toBytes(productId));
    }

    private static Set<UUID> recipientsOf(List<JsonNode> payloads) {
        Set<UUID> recipients = new HashSet<>();
        payloads.forEach(payload -> payload.get("recipientIds").forEach(id -> recipients.add(UUID.fromString(id.asString()))));
        return recipients;
    }

    private List<JsonNode> notifications(UUID productId) {
        List<JsonNode> payloads = new ArrayList<>();
        jdbcTemplate.queryForList("""
                SELECT payload FROM preorder_outbox_events WHERE event_type = ? AND aggregate_id = ? ORDER BY id
                """, String.class, NOTIFICATION, UuidBinary.toBytes(productId))
                .forEach(payload -> payloads.add(jsonMapper.readTree(payload)));
        return payloads;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("기다림 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
