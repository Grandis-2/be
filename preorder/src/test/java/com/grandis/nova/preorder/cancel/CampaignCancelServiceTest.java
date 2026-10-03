package com.grandis.nova.preorder.cancel;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.cancel.application.CancelStarter;
import com.grandis.nova.preorder.event.ExternalJobSucceeded;
import com.grandis.nova.preorder.event.PreorderEventHandler;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@PreorderIntegrationTest
class CampaignCancelServiceTest {

    @Autowired
    CampaignCancelService campaignCancelService;

    @MockitoSpyBean
    CancelStarter cancelStarter;

    @MockitoSpyBean
    PreorderLedger ledger;

    @MockitoSpyBean
    Campaigns campaigns;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    PreorderEventHandler handler;

    @Autowired
    Preorders preorders;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    AcceptFixtures accepts;
    PreorderProduct product;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        product = fixtures.openPreorderProduct();
    }

    @Test
    void 판매_중지하면_회차를_마감하고_진행_중_예약만_관리자_사유로_취소를_시작한다() {
        Long pending = accept().preorder().id();
        AcceptResult payable = accept();
        handler.onExternalJobSucceeded(new ExternalJobSucceeded(
                fixtures.workerSucceeds(payable.preorder().id(), "REGISTER"),
                AcceptFixtures.tokenOf(payable), "REGISTER", "R-" + ShopFixtures.unique()));
        Long alreadyCanceling = accept().preorder().id();
        cancelStarter.start(preorders.findById(alreadyCanceling).orElseThrow(), EventActor.USER, null,
                CancelReason.USER);
        Instant before = Instant.now();

        campaignCancelService.cancel(product.productId(), "공급 차질로 사전예약 취소");

        assertThat(closesAt()).isBeforeOrEqualTo(Instant.now()).isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(statuses()).containsOnly("CANCELING");
        for (Long id : List.of(pending, payable.preorder().id())) {
            assertThat(cancelingEvents(id)).containsExactly(Map.of("actor", "ADMIN", "reason", "공급 차질로 사전예약 취소"));
            assertThat(outboxReasons(id)).containsExactly("CAMPAIGN_CANCELED");
        }
        assertThat(outboxReasons(alreadyCanceling)).as("이미 취소 중이면 새로 시작하지 않는다").containsExactly("USER");
    }

    @Test
    void 판매_중지하면_catalog_캐시를_비우고_뒤의_접수는_마감으로_거절된다() {
        accept();

        campaignCancelService.cancel(product.productId(), "공급 차질");

        assertThatThrownBy(this::accept)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(PreorderErrorCode.SALE_CLOSED);
        verify(catalogClient, times(2)).getProduct(product.productId());
    }

    @Test
    void 한_묶음보다_많아도_모두_취소한다() {
        IntStream.rangeClosed(0, CampaignCancelService.BATCH_SIZE).forEach(i -> accept());

        campaignCancelService.cancel(product.productId(), "공급 차질");

        assertThat(statuses()).hasSize(CampaignCancelService.BATCH_SIZE + 1).containsOnly("CANCELING");
    }

    @Test
    void 중간에_멈춘_뒤_같은_이벤트를_다시_받으면_남은_예약만_이어서_취소한다() {
        List<Long> ids = IntStream.rangeClosed(0, CampaignCancelService.BATCH_SIZE)
                .mapToObj(i -> accept().preorder().id())
                .toList();
        AtomicInteger starts = new AtomicInteger();
        willAnswer(invocation -> {
            if (starts.incrementAndGet() == CampaignCancelService.BATCH_SIZE + 1) {
                throw new IllegalStateException("두 번째 묶음에서 한 번 멈춤");
            }
            return invocation.callRealMethod();
        }).given(AopTestUtils.<CancelStarter>getUltimateTargetObject(cancelStarter)).start(any(), any(), any(), any());

        assertThatThrownBy(() -> campaignCancelService.cancel(product.productId(), "공급 차질"))
                .isInstanceOf(IllegalStateException.class);
        Instant closedAt = closesAt();
        assertThat(statuses()).containsOnlyOnce("PENDING_SYNC");

        campaignCancelService.cancel(product.productId(), "공급 차질");

        assertThat(statuses()).containsOnly("CANCELING");
        assertThat(ids).allSatisfy(id -> {
            assertThat(cancelingEvents(id)).hasSize(1);
            assertThat(outboxReasons(id)).containsExactly("CAMPAIGN_CANCELED");
        });
        assertThat(closesAt()).as("다시 받아도 첫 마감 시각을 유지한다").isEqualTo(closedAt);
    }

    /** 접수가 회차를 먼저 잠그면 판매 중지는 그 커밋을 기다렸다가, 방금 들어온 예약까지 취소한다. */
    @Test
    void 접수가_회차를_먼저_잠그면_판매_중지는_기다렸다가_그_예약까지_취소한다() throws Exception {
        accepts.stubCatalog(product);
        Long customer = fixtures.customer();
        CountDownLatch acceptLocked = new CountDownLatch(1);
        CountDownLatch cancelWaiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        willAnswer(invocation -> {
            acceptLocked.countDown();
            release.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).given(AopTestUtils.<PreorderLedger>getUltimateTargetObject(ledger)).accept(any(), any(), any());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AcceptResult> accepting = executor.submit(() -> accepts.submit(customer, product));
            Future<?> canceling;
            try {
                assertThat(acceptLocked.await(10, TimeUnit.SECONDS)).isTrue();
                // 접수는 이미 회차를 잠갔으므로 이 뒤의 회차 닫기는 판매 중지의 것이다. 실제 동작은 그대로 둔다
                Campaigns target = AopTestUtils.getUltimateTargetObject(campaigns);
                Answer<?> delegate = mockingDetails(target).getMockCreationSettings().getDefaultAnswer();
                willAnswer(invocation -> {
                    cancelWaiting.countDown();
                    return delegate.answer(invocation);
                }).given(target).closeNow(any(), any());
                canceling = executor.submit(() -> campaignCancelService.cancel(product.productId(), "공급 차질"));
                assertThat(cancelWaiting.await(10, TimeUnit.SECONDS)).isTrue();
                await().alias("접수가 회차를 잠근 동안 판매 중지는 끝나지 않는다")
                        .during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5))
                        .until(() -> !canceling.isDone());
            } finally {
                release.countDown();
            }

            Long preorderId = accepting.get(10, TimeUnit.SECONDS).preorder().id();
            canceling.get(10, TimeUnit.SECONDS);
            assertThat(cancelingEvents(preorderId)).hasSize(1);
            assertThat(statuses()).containsExactly("CANCELING");
        }
    }

    @Test
    void 오픈_전_회차는_기간_전체를_지난_것으로_닫는다() {
        Instant now = Instant.now();
        PreorderProduct upcoming = fixtures.preorderProduct(now.plusSeconds(3600), now.plusSeconds(7200));

        campaignCancelService.cancel(upcoming.productId(), "공급 차질");

        Map<String, Object> period = jdbcTemplate.queryForMap(
                "SELECT opens_at, closes_at FROM preorder_campaigns WHERE product_id = ?", upcoming.productId());
        Instant opensAt = toInstant(period.get("opens_at"));
        Instant closesAt = toInstant(period.get("closes_at"));
        assertThat(opensAt).isBefore(closesAt);
        assertThat(closesAt).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void 사유가_비었으면_기본_문구를_길면_500자로_잘라_남긴다() {
        Long blank = accept().preorder().id();
        campaignCancelService.cancel(product.productId(), " ");
        assertThat(cancelingEvents(blank).getFirst().get("reason")).isEqualTo("사전예약 회차 판매 중지");

        PreorderProduct other = fixtures.openPreorderProduct();
        Long longReason = accepts.accept(fixtures.customer(), other).preorder().id();
        campaignCancelService.cancel(other.productId(), "가".repeat(600));
        assertThat((String) cancelingEvents(longReason).getFirst().get("reason")).hasSize(500);
    }

    private AcceptResult accept() {
        return accepts.accept(fixtures.customer(), product);
    }

    private Instant closesAt() {
        return toInstant(jdbcTemplate.queryForObject(
                "SELECT closes_at FROM preorder_campaigns WHERE product_id = ?", LocalDateTime.class,
                product.productId()));
    }

    private List<String> statuses() {
        return jdbcTemplate.queryForList("SELECT status FROM preorders WHERE product_id = ?", String.class,
                product.productId());
    }

    private List<Map<String, Object>> cancelingEvents(Long preorderId) {
        return jdbcTemplate.queryForList("""
                SELECT actor, reason FROM preorder_events WHERE preorder_id = ? AND to_status = 'CANCELING'
                """, preorderId);
    }

    private List<String> outboxReasons(Long preorderId) {
        return jdbcTemplate.queryForList("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reason')) FROM preorder_outbox_events
                 WHERE event_type = 'PREORDER_CANCEL_REQUESTED' AND aggregate_id = ?
                """, String.class, preorderId);
    }

    private static Instant toInstant(Object value) {
        return ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }
}
