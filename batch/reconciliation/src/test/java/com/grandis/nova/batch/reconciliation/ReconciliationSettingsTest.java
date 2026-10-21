package com.grandis.nova.batch.reconciliation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.grandis.nova.batch.reconciliation.check.Reconciler;
import com.grandis.nova.batch.reconciliation.config.MockLedgerProperties;
import com.grandis.nova.batch.reconciliation.config.ReconciliationProperties;
import com.grandis.nova.batch.reconciliation.mock.MockLedgerClient;
import com.grandis.nova.batch.reconciliation.run.RunStore;
import com.grandis.nova.batch.reconciliation.snapshot.BeSnapshot;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.unit.DataSize;

/** 실행이 제한 시간 + 몇 초 안에 끝나려면 DB 조회 없이 도는 Mock 목록 구간이 제한 시간 안에 끝나야 한다 */
class ReconciliationSettingsTest {

    @Test
    void 제한_시간이_Mock_목록_전체_기한보다_짧으면_뜨지_않는다() {
        assertThatThrownBy(() -> service(Duration.ofMinutes(5), Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nova.batch.reconciliation.run-timeout 은 external.mock.max-duration 이상이어야 한다");
    }

    @Test
    void 제한_시간이_Mock_목록_전체_기한과_같으면_뜬다() {
        assertThatCode(() -> service(Duration.ofMinutes(10), Duration.ofMinutes(10))).doesNotThrowAnyException();
    }

    private static ReconciliationService service(Duration runTimeout, Duration mockMaxDuration) {
        ReconciliationProperties properties = new ReconciliationProperties(true, "0 0 4 * * *", ZoneId.of("Asia/Seoul"),
                Duration.ofSeconds(5), Duration.ofMinutes(5), Duration.ofDays(1), Duration.ofMinutes(2),
                Duration.ofMinutes(45), Duration.ofHours(1), 1000, 10000, runTimeout);
        MockLedgerProperties mockLedger = new MockLedgerProperties(URI.create("http://localhost:8081"),
                Duration.ofSeconds(2), Duration.ofSeconds(10), 1000, DataSize.ofMegabytes(8), mockMaxDuration, 2,
                Duration.ofSeconds(1));
        return new ReconciliationService(mock(RunStore.class), mock(BeSnapshot.class), mock(MockLedgerClient.class),
                mock(Reconciler.class), mock(CampaignCalendar.class), properties, mockLedger, Clock.systemUTC(),
                mock(PlatformTransactionManager.class));
    }
}
