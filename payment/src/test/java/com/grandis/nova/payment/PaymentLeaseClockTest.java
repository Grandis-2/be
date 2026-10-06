package com.grandis.nova.payment;

import com.grandis.nova.common.jpa.StorageClock;
import com.grandis.nova.payment.domain.model.Outcome;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.support.PaymentFixtures;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.ProviderError;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리스 만료 · 재시도 시각은 앱 시계가 아니라 DB 시각으로 쓴다. 앱 시계를 먼 과거로 고정해도 두 시각이 "DB 지금 + 간격" 이고,
 * 그래서 선점 직후 반영이 된다. 앱 시계로 계산했다면 만료가 과거라 반영이 리스를 잃는다.
 * 기록용 시각(requested_at)은 앱 시계를 따른다.
 */
@PaymentIntegrationTest
class PaymentLeaseClockTest {

    static final Instant APP_NOW = Instant.parse("2000-01-01T00:00:00Z");
    static final Money AMOUNT = Money.won(1000);

    @TestBean(name = "storageClock", methodName = "farPastClock")
    Clock storageClock;

    @Autowired
    PaymentLedger ledger;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    static Clock farPastClock() {
        return StorageClock.atStorageResolution(Clock.fixed(APP_NOW, ZoneOffset.UTC));
    }

    @Test
    void leaseFollowsDatabaseClockWhileRecordsFollowAppClock() {
        ClaimedTransaction started = start();

        assertThat(started.transaction().requestedAt()).isEqualTo(APP_NOW);
        assertThat(secondsFromDatabaseNow("lease_expires_at", started)).isBetween(PaymentTransaction.LEASE.toSeconds() - 5,
                PaymentTransaction.LEASE.toSeconds());
        transactionTemplate.executeWithoutResult(s -> ledger.resolve(started, new Outcome.Confirmed(APP_NOW)));
    }

    @Test
    void retryTimeFollowsDatabaseClock() {
        ClaimedTransaction started = start();

        transactionTemplate.executeWithoutResult(s -> ledger.resolve(started,
                new Outcome.InProgress(new ProviderError("IDEMPOTENT_REQUEST_PROCESSING", null), Duration.ofSeconds(300))));

        assertThat(secondsFromDatabaseNow("next_retry_at", started)).isBetween(295L, 300L);
    }

    private ClaimedTransaction start() {
        PaymentTransaction pending = transactionTemplate.execute(s ->
                ledger.openCapture(PaymentFixtures.newOrderTarget(), AMOUNT));
        return transactionTemplate.execute(s -> ledger.start(pending, pending.target(), PaymentFixtures.newProviderPayment(),
                        AMOUNT))
                .orElseThrow();
    }

    private long secondsFromDatabaseNow(String column, ClaimedTransaction claimed) {
        return jdbcTemplate.queryForObject("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), " + column
                + ") FROM payment_transactions WHERE id = ?", Long.class, claimed.transaction().id());
    }
}
