package com.grandis.nova.preorder.cancel.application;

import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.cancel.ExpiryCancelService;
import com.grandis.nova.preorder.event.PreorderEventHandler;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PaymentOverdue;
import com.grandis.nova.preorder.preorder.PreorderFact;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * 결제 기한이 지난 예약을 고르고 만료 취소를 시작한다. 공유 DB 에는 다른 시험이 남긴 기한 지난 예약이 섞이므로,
 * 결과는 이 시험이 만든 예약이 들었는지 · 빠졌는지로, 수는 앞뒤 증가분으로 보고, 작업은 한 번에 집는 수를 넉넉히 둔다.
 */
@PreorderIntegrationTest
class PaymentExpiryJobTest {

    static final String CANCEL_REQUESTED = "PREORDER_CANCEL_REQUESTED";
    static final int WIDE_BATCH = 1000;

    @Autowired
    Preorders preorders;

    @Autowired
    PreorderLedger ledger;

    @Autowired
    ExpiryCancelService expiryCancelService;

    @Autowired
    CancelStarter cancelStarter;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    PreorderEventHandler handler;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Clock clock;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    AcceptFixtures accepts;
    SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void 기한이_지난_결제_가능_예약만_오래된_것부터_고른다() {
        Booking older = overdue();
        fixtures.passPaymentDue(older.id());
        Booking overdue = overdue();
        Booking notYetDue = registered();
        Booking pending = accepted();
        Booking reserved = overdue();
        fire(reserved.id(), new PreorderFact.PaymentConfirmed(Instant.now()));
        Booking canceling = overdue();
        cancelStarter.start(preorders.getById(canceling.id()), EventActor.USER, null, CancelReason.USER);

        List<String> found = preorders.findPaymentOverdue(Instant.now(), WIDE_BATCH);

        assertThat(found).contains(older.token(), overdue.token())
                .doesNotContain(notYetDue.token(), pending.token(), reserved.token(), canceling.token());
        assertThat(found.indexOf(older.token())).as("기한이 오래된 것부터").isLessThan(found.indexOf(overdue.token()));
    }

    @Test
    void 한_번에_고르는_수를_넘지_않는다() {
        overdue();
        overdue();

        assertThat(preorders.findPaymentOverdue(Instant.now(), 1)).hasSize(1);
    }

    /** 결제됐는데 결제 확인을 못 받은 예약이면 만료 → 거절이 되풀이된다. 다시 고르지 않고 따로 센다. */
    @Test
    void 주문_쪽에서_취소가_거절된_적이_있는_예약은_고르지_않고_막힌_수로_센다() {
        PaymentOverdue before = preorders.countPaymentOverdue(Instant.now());
        Booking rejected = overdue();
        expiryCancelService.expire(rejected.token());
        fire(rejected.id(), new PreorderFact.CancelRejected("REJECTED_BY_ORDER:PAID", null));
        Booking overdue = overdue();

        assertThat(status(rejected)).as("결제 시각 없이 거절돼 결제 가능으로 돌아왔다").isEqualTo(PreorderStatus.REGISTERED);
        assertThat(preorders.findPaymentOverdue(Instant.now(), WIDE_BATCH))
                .contains(overdue.token()).doesNotContain(rejected.token());
        PaymentOverdue after = preorders.countPaymentOverdue(Instant.now());
        assertThat(after.overdue() - before.overdue()).isEqualTo(1);
        assertThat(after.blocked() - before.blocked()).isEqualTo(1);
    }

    @Test
    void 한_주기에_기한이_지난_예약의_만료_취소를_시작한다() {
        Booking overdue = overdue();
        Booking notYetDue = registered();

        int started = job(expiryCancelService).expireOverdue();

        assertThat(status(overdue)).isEqualTo(PreorderStatus.CANCELING);
        assertThat(fixtures.outboxEvents(CANCEL_REQUESTED, overdue.id())).isEqualTo(1);
        assertThat(status(notYetDue)).isEqualTo(PreorderStatus.REGISTERED);
        assertThat(started).isPositive();
        assertThat(meterRegistry.get(PaymentExpiryJob.STARTED_METRIC).counter().count()).isEqualTo(started);
        assertThat(preorders.findPaymentOverdue(Instant.now(), WIDE_BATCH)).as("다음 주기에는 빠진다")
                .doesNotContain(overdue.token());
    }

    /** 만료 → 거절(결제 시각 없음)로 돌아온 예약을 다음 주기가 다시 만료하지 않는다. */
    @Test
    void 만료가_거절돼_돌아온_예약은_다음_주기에_다시_만료하지_않는다() {
        Booking overdue = overdue();
        PaymentExpiryJob job = job(expiryCancelService);
        job.expireOverdue();
        fire(overdue.id(), new PreorderFact.CancelRejected("REJECTED_BY_ORDER:PAID", null));

        job.expireOverdue();

        assertThat(status(overdue)).isEqualTo(PreorderStatus.REGISTERED);
        assertThat(fixtures.outboxEvents(CANCEL_REQUESTED, overdue.id())).as("취소 요청은 처음 한 번뿐").isEqualTo(1);
    }

    @Test
    void 작업이_동시에_돌아도_예약마다_취소는_한_번만_시작된다() throws Exception {
        List<Booking> bookings = List.of(overdue(), overdue(), overdue());
        PaymentExpiryJob job = job(expiryCancelService);

        List<Outcome<Integer>> outcomes = Concurrently.run(2, i -> job::expireOverdue);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        for (Booking booking : bookings) {
            assertThat(status(booking)).isEqualTo(PreorderStatus.CANCELING);
            assertThat(fixtures.outboxEvents(CANCEL_REQUESTED, booking.id())).isEqualTo(1);
        }
    }

    @Test
    void 한_건이_실패해도_나머지는_시작하고_실패를_센다() {
        Booking failing = overdue();
        Booking overdue = overdue();
        ExpiryCancelService flaky = mock(ExpiryCancelService.class, delegatesTo(expiryCancelService));
        doThrow(new IllegalStateException("만료 실패")).when(flaky).expire(failing.token());

        job(flaky).expireOverdue();

        assertThat(status(failing)).as("다음 주기에 다시 집는다").isEqualTo(PreorderStatus.REGISTERED);
        assertThat(status(overdue)).isEqualTo(PreorderStatus.CANCELING);
        assertThat(meterRegistry.get(PaymentExpiryJob.FAILED_METRIC).counter().count()).isEqualTo(1);
    }

    /** 원장은 호출하는 쪽 트랜잭션 안에서만 사건을 받는다. */
    private void fire(UUID preorderId, PreorderFact fact) {
        transactionTemplate.executeWithoutResult(status -> ledger.fire(preorderId, fact));
    }

    private PaymentExpiryJob job(ExpiryCancelService service) {
        return new PaymentExpiryJob(preorders, service,
                new PaymentExpiryProperties(true, Duration.ofMinutes(1), WIDE_BATCH), meterRegistry, clock);
    }

    /** 결제 기한(결제 가능 뒤 24시간)이 지난 예약. */
    private Booking overdue() {
        Booking booking = registered();
        fixtures.passPaymentDue(booking.id());
        return booking;
    }

    /** 외부 등록을 확인해 결제 가능으로 만든 예약. */
    private Booking registered() {
        Booking booking = accepted();
        handler.onExternalJobSucceeded(ShopFixtures.unique(), ShopFixtures.succeeded(
                fixtures.workerSucceeds(booking.id(), "REGISTER"), booking.token(), "REGISTER", "R-" + ShopFixtures.unique()));
        return booking;
    }

    private Booking accepted() {
        AcceptResult accepted = accepts.accept(fixtures.customer());
        return new Booking(accepted.preorder().id(), AcceptFixtures.tokenOf(accepted));
    }

    private PreorderStatus status(Booking booking) {
        return preorders.getById(booking.id()).status();
    }

    record Booking(UUID id, String token) {
    }
}
