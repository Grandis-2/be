package com.grandis.nova.waitingroom.entry;

import com.grandis.nova.waitingroom.domain.product.MaxWait;
import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.SalesWindow;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EtaCreditTest {

    static final SalesWindow WINDOW = new SalesWindow(Instant.parse("2026-10-10T01:00:00Z"), Instant.parse("2026-10-10T02:00:00Z"));

    @Test
    void 모델_몫을_알면_그것으로_잰다() {
        assertThat(EtaCredit.of(ProductState.withQueue(40, 10, WINDOW, 150), meta(150))).isEqualTo(40);
    }

    @Test
    void 급증_직후_몫을_모르면_전역_속도와_모델_상한_중_작은_쪽으로_잰다() {
        assertThat(EtaCredit.of(ProductState.withQueue(0, 10, WINDOW, 150), meta(500))).isEqualTo(150);
        assertThat(EtaCredit.of(ProductState.withQueue(0, 10, WINDOW, ProductState.UNLIMITED_CAP), meta(500))).isEqualTo(500);
    }

    @Test
    void 비공개로_멈춘_줄은_맨_앞이어도_모름이다() {
        assertThat(EtaCredit.etaSec(0, ProductState.hidden(10, WINDOW, 150), meta(500))).isNaN();
        assertThat(EtaCredit.etaSec(5, ProductState.hidden(10, WINDOW, 150), meta(500))).isNaN();
        assertThat(EtaCredit.etaSec(0, ProductState.withQueue(40, 10, WINDOW, 150), meta(500))).isZero();
    }

    @Test
    void 입장을_멈췄으면_모름_그대로다() {
        assertThat(EtaCredit.of(ProductState.withQueue(0, 10, WINDOW, 150), meta(0))).isZero();
    }

    private static SnapshotMeta meta(long globalCredit) {
        return new SnapshotMeta(globalCredit, 1, MaxWait.unlimited());
    }
}
