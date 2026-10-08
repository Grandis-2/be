package com.grandis.nova.waitingroom.domain.admission;

import com.grandis.nova.waitingroom.domain.product.MaxWait;
import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.SalesWindow;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

import static com.grandis.nova.waitingroom.support.TestIds.productKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmissionDeciderTest {

    static final Instant OPENS_AT = Instant.parse("2026-10-10T01:00:00Z");
    static final Instant NOW = OPENS_AT.plusSeconds(5);
    static final SalesWindow WINDOW = new SalesWindow(OPENS_AT, OPENS_AT.plus(Duration.ofHours(14)));
    static final long NO_CAP = ProductState.UNLIMITED_CAP;
    static final SnapshotMeta META = new SnapshotMeta(100, 1, MaxWait.unlimited());
    static final ProductState IDLE = ProductState.idle(WINDOW, NO_CAP);

    private final AdmissionDecider decider = new AdmissionDecider(new SecondWindowLimiter(1_000), 0.7);

    private static AdmissionRequest request(ProductState state) {
        return new AdmissionRequest(productKey(101), state, META, NOW, false, false, false);
    }

    @Nested
    class 접수_기간 {

        @Test
        void 오픈_전이나_마감_뒤면_다른_무엇보다_먼저_거절한다() {
            ProductState queued = ProductState.withQueue(5, 50, WINDOW, NO_CAP);

            assertThat(decider.decide(new AdmissionRequest(productKey(101), queued, META, OPENS_AT.minusSeconds(1), true, true, true)))
                    .isEqualTo(AdmissionDecision.REJECT_NOT_OPEN);
            assertThat(decider.decide(new AdmissionRequest(productKey(101), queued, META, WINDOW.closesAt(), true, true, true)))
                    .isEqualTo(AdmissionDecision.REJECT_CLOSED);
        }

        @Test
        void 리더가_닫은_모델은_기간_안이어도_마감이다() {
            assertThat(decider.decide(request(ProductState.closed(10, WINDOW, NO_CAP)))).isEqualTo(AdmissionDecision.REJECT_CLOSED);
        }
    }

    @Nested
    class 줄_상한 {

        @Test
        void 기본_제한_없음이면_줄이_아무리_길어도_거절하지_않는다() {
            assertThat(decider.decide(request(ProductState.withQueue(1, 1_000_000, WINDOW, NO_CAP))))
                    .isEqualTo(AdmissionDecision.ENQUEUE_BACKLOG);
        }

        @Test
        void 최대_대기_시간을_정하면_그_안에_못_들어갈_사람은_받지_않는다() {
            SnapshotMeta limited = new SnapshotMeta(100, 1, MaxWait.of(Duration.ofSeconds(60)));
            ProductState full = ProductState.withQueue(10, 600, WINDOW, NO_CAP);

            assertThat(decider.decide(new AdmissionRequest(productKey(101), full, limited, NOW, false, false, false)))
                    .isEqualTo(AdmissionDecision.REJECT_QUEUE_FULL);
        }

        @Test
        void 낡았으면_이_노드가_방금_본_가득으로도_거절하고_신선하면_그_기억을_안_본다() {
            assertThat(decider.decide(new AdmissionRequest(productKey(101), IDLE, META, NOW, true, false, true)))
                    .isEqualTo(AdmissionDecision.REJECT_QUEUE_FULL);
            assertThat(decider.decide(new AdmissionRequest(productKey(101), IDLE, META, NOW, false, false, true)))
                    .as("신선하면 가득 기억을 안 본다").isEqualTo(AdmissionDecision.PASS_UNDER_CAP);
        }

        @Test
        void 이번_틱_몫이_0_인_모델도_최소_속도만큼은_줄에_받는다() {
            SnapshotMeta limited = new SnapshotMeta(100, 1, MaxWait.of(Duration.ofSeconds(60)));
            ProductState starving = ProductState.withQueue(0, 1, WINDOW, NO_CAP);

            assertThat(decider.decide(new AdmissionRequest(productKey(101), starving, limited, NOW, false, false, false)))
                    .isEqualTo(AdmissionDecision.ENQUEUE_BACKLOG);
            assertThat(decider.decide(new AdmissionRequest(productKey(101), ProductState.withQueue(0, 60, WINDOW, NO_CAP), limited,
                    NOW, false, false, false))).isEqualTo(AdmissionDecision.REJECT_QUEUE_FULL);
        }

        @Test
        void 줄_세우기_경로는_배수_속도를_모르면_가장_낮은_속도로_잰다() {
            MaxWait minute = MaxWait.of(Duration.ofSeconds(60));

            assertThat(decider.queueCapacity(IDLE, minute)).isEqualTo(60);
            assertThat(decider.queueCapacity(ProductState.withQueue(10, 50, WINDOW, NO_CAP), minute)).isEqualTo(600);
            assertThat(decider.queueCapacity(IDLE, MaxWait.unlimited())).isEqualTo(Long.MAX_VALUE);
        }
    }

    @Nested
    class 추월_금지 {

        @Test
        void 재료가_낡았으면_비어_보여도_줄에_세운다() {
            assertThat(decider.decide(new AdmissionRequest(productKey(101), IDLE, META, NOW, true, false, false)))
                    .isEqualTo(AdmissionDecision.ENQUEUE_STALE);
        }

        @Test
        void 줄이_있거나_방금_줄에_세웠으면_뒤에_선다() {
            assertThat(decider.decide(request(ProductState.withQueue(50, 10, WINDOW, NO_CAP))))
                    .isEqualTo(AdmissionDecision.ENQUEUE_BACKLOG);
            assertThat(decider.decide(new AdmissionRequest(productKey(101), IDLE, META, NOW, false, true, false)))
                    .isEqualTo(AdmissionDecision.ENQUEUE_BACKLOG);
        }

        @Test
        void 줄이_있는_어떤_상태에서도_통과시키지_않는다() {
            Random random = new Random(42);
            for (int i = 0; i < 2_000; i++) {
                long waiting = 1 + random.nextInt(10_000);
                long credit = random.nextInt(10_000);
                ProductState queued = ProductState.withQueue(credit, waiting, WINDOW, NO_CAP);
                AdmissionRequest request = new AdmissionRequest("p" + random.nextInt(50), queued,
                        new SnapshotMeta(random.nextInt(100_000), random.nextInt(10), MaxWait.unlimited()),
                        NOW, random.nextBoolean(), random.nextBoolean(), random.nextBoolean());

                assertThat(decider.decide(request).isPass()).as("waiting=%d credit=%d", waiting, credit).isFalse();
            }
        }
    }

    @Nested
    class 한산_통과 {

        @Test
        void 한산하면_상한_안에서_줄_없이_통과하고_넘으면_모델_몫으로_줄에_선다() {
            ProductState capped = ProductState.idle(WINDOW, 2);

            assertThat(decider.decide(request(capped))).isEqualTo(AdmissionDecision.PASS_UNDER_CAP);
            assertThat(decider.decide(request(capped))).isEqualTo(AdmissionDecision.PASS_UNDER_CAP);
            assertThat(decider.decide(request(capped))).isEqualTo(AdmissionDecision.ENQUEUE_RATE_PRODUCT);
        }

        @Test
        void 노드_몫을_다_쓰면_노드_고갈로_줄에_선다() {
            SnapshotMeta tight = new SnapshotMeta(2, 1, MaxWait.unlimited());
            AdmissionDecider generous = new AdmissionDecider(new SecondWindowLimiter(1_000), 10.0);

            assertThat(generous.decide(new AdmissionRequest("a", IDLE, tight, NOW, false, false, false))).isEqualTo(AdmissionDecision.PASS_UNDER_CAP);
            assertThat(generous.decide(new AdmissionRequest("b", IDLE, tight, NOW, false, false, false))).isEqualTo(AdmissionDecision.PASS_UNDER_CAP);
            assertThat(generous.decide(new AdmissionRequest("c", IDLE, tight, NOW, false, false, false))).isEqualTo(AdmissionDecision.ENQUEUE_RATE_GLOBAL);
        }

        @Test
        void 리미터가_키를_더_못_들면_키_포화로_줄에_선다() {
            AdmissionDecider small = new AdmissionDecider(new SecondWindowLimiter(2), 0.7);

            assertThat(small.decide(new AdmissionRequest("a", IDLE, META, NOW, false, false, false))).isEqualTo(AdmissionDecision.PASS_UNDER_CAP);
            assertThat(small.decide(new AdmissionRequest("b", IDLE, META, NOW, false, false, false))).isEqualTo(AdmissionDecision.ENQUEUE_KEY_SATURATED);
        }
    }

    @Test
    void 판정_결과는_통과_줄_거절_중_하나에만_속한다() {
        for (AdmissionDecision decision : AdmissionDecision.values()) {
            int kinds = (decision.isPass() ? 1 : 0) + (decision.isEnqueue() ? 1 : 0) + (decision.isReject() ? 1 : 0);
            assertThat(kinds).as(decision.name()).isEqualTo(1);
        }
    }

    @Test
    void 잘못된_비율이나_재료는_만들지_않는다() {
        assertThatThrownBy(() -> new AdmissionDecider(new SecondWindowLimiter(10), -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionRequest(" ", IDLE, META, NOW, false, false, false)).isInstanceOf(IllegalArgumentException.class);
    }
}
