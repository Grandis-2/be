package com.grandis.nova.preorder.accept.application;

import com.grandis.nova.preorder.integration.DependencyUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class AcceptBulkheadTest {

    static final UUID PRODUCT_A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    static final UUID PRODUCT_B = UUID.fromString("00000000-0000-7000-8000-00000000000b");
    static final UUID PRODUCT_C = UUID.fromString("00000000-0000-7000-8000-00000000000c");
    static final Duration SHORT_WAIT = Duration.ofMillis(200);
    static final Function<UUID, String> NO_PRODUCT_TAG = productId -> AcceptProductTags.OTHER;

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        executor.shutdownNow();
    }

    @Test
    void 같은_상품은_허가_수만큼만_들어가고_넘치면_503_과_흩은_Retry_After() throws Exception {
        AcceptBulkhead bulkhead = bulkhead(2, 10);
        holdUntilReleased(bulkhead, PRODUCT_A, 2);

        assertThatThrownBy(() -> bulkhead.call(PRODUCT_A, () -> "late"))
                .isInstanceOfSatisfying(DependencyUnavailableException.class, e ->
                        assertThat(e.retryAfter()).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(3)));
        assertThat(registry.get(AcceptBulkhead.REJECTED_METRIC).tag("limit", "product").counter().count())
                .isEqualTo(1);
        assertThat(registry.get(AcceptBulkhead.WAIT_METRIC).tag("outcome", "rejected").timer()
                .totalTime(TimeUnit.MILLISECONDS)).as("거절도 기다린 시간을 남긴다").isGreaterThanOrEqualTo(150);
    }

    @Test
    void 거절_건수는_받은_상품_라벨로_센다() throws Exception {
        AcceptBulkhead bulkhead = new AcceptBulkhead(properties(1, 10), 20, registry,
                productId -> PRODUCT_A.equals(productId) ? productId.toString() : AcceptProductTags.OTHER);
        holdUntilReleased(bulkhead, PRODUCT_A, 1);

        assertThatThrownBy(() -> bulkhead.call(PRODUCT_A, () -> "late"))
                .isInstanceOf(DependencyUnavailableException.class);
        assertThat(registry.get(AcceptBulkhead.REJECTED_METRIC).tag("limit", "product")
                .tag(AcceptProductTags.PRODUCT_TAG, PRODUCT_A.toString()).counter().count()).isEqualTo(1);
        assertThat(registry.get(AcceptBulkhead.REJECTED_METRIC).tag("limit", "product")
                .tag(AcceptProductTags.PRODUCT_TAG, AcceptProductTags.OTHER).counter().count())
                .as("거절이 없어도 0 으로 있다").isZero();
    }

    @Test
    void 한_상품이_막혀도_다른_상품은_들어간다() throws Exception {
        AcceptBulkhead bulkhead = bulkhead(2, 10);
        holdUntilReleased(bulkhead, PRODUCT_A, 2);

        assertThat(bulkhead.call(PRODUCT_B, () -> "other")).isEqualTo("other");
    }

    @Test
    void 인스턴스_전체_상한을_넘으면_다른_상품도_503() throws Exception {
        AcceptBulkhead bulkhead = bulkhead(2, 3);
        holdUntilReleased(bulkhead, PRODUCT_A, 2);
        holdUntilReleased(bulkhead, PRODUCT_B, 1);

        assertThatThrownBy(() -> bulkhead.call(PRODUCT_C, () -> "late"))
                .isInstanceOf(DependencyUnavailableException.class);
        assertThat(registry.get(AcceptBulkhead.REJECTED_METRIC).tag("limit", "instance").counter().count())
                .isEqualTo(1);
    }

    @Test
    void 상품_허가를_기다리는_요청은_전체_허가를_쥐지_않는다() throws Exception {
        AcceptBulkhead bulkhead = new AcceptBulkhead(new AcceptBulkheadProperties(1, 0.5, 2, Duration.ofSeconds(5),
                Duration.ofSeconds(1), Duration.ofSeconds(3)), 10, registry, NO_PRODUCT_TAG);
        holdUntilReleased(bulkhead, PRODUCT_A, 1);
        // 상품 A 를 기다리는 요청이 여럿이어도 전체 허가 1 이 남아 상품 B 가 들어간다
        List<Thread> waiting = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            waiting.add(Thread.ofVirtual().start(() -> bulkhead.call(PRODUCT_A, () -> "a")));
        }
        await().atMost(Duration.ofSeconds(5))
                .until(() -> waiting.stream().allMatch(thread -> thread.getState() == Thread.State.TIMED_WAITING));

        assertThat(bulkhead.call(PRODUCT_B, () -> "b")).isEqualTo("b");
    }

    @Test
    void 일이_예외로_끝나도_허가를_돌려준다() {
        AcceptBulkhead bulkhead = bulkhead(1, 1);

        assertThatThrownBy(() -> bulkhead.call(PRODUCT_A, () -> {
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(bulkhead.call(PRODUCT_A, () -> "next")).isEqualTo("next");
    }

    @Test
    void 전체_상한은_직접_주지_않으면_풀의_비율_내림_최소_1() {
        assertThat(properties(2, 0).instanceLimit(10)).isEqualTo(5);
        assertThat(properties(2, 0).instanceLimit(5)).isEqualTo(2);
        assertThat(properties(2, 0).instanceLimit(1)).isEqualTo(1);
        assertThat(properties(2, 7).instanceLimit(10)).isEqualTo(7);
    }

    @Test
    void 직접_준_전체_상한이_풀_크기보다_작지_않으면_기동에서_막는다() {
        assertThatThrownBy(() -> properties(2, 10).instanceLimit(10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AcceptBulkhead(properties(2, 11), 10, registry, NO_PRODUCT_TAG))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 잘못된_설정은_기동에서_막는다() {
        assertThatThrownBy(() -> new AcceptBulkheadProperties(0, 0.5, 0, SHORT_WAIT, Duration.ofSeconds(1),
                Duration.ofSeconds(3))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AcceptBulkheadProperties(2, 0, 0, SHORT_WAIT, Duration.ofSeconds(1),
                Duration.ofSeconds(3))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AcceptBulkheadProperties(2, 1, 0, SHORT_WAIT, Duration.ofSeconds(1),
                Duration.ofSeconds(3))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AcceptBulkheadProperties(2, 0.5, 0, SHORT_WAIT, Duration.ofSeconds(3),
                Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AcceptBulkheadProperties(2, 0.5, 0, SHORT_WAIT, Duration.ZERO,
                Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 허가를 count 개 얻어 release 까지 쥔다. 모두 들어간 뒤에 돌아온다. */
    private void holdUntilReleased(AcceptBulkhead bulkhead, UUID productId, int count) throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
            executor.submit(() -> bulkhead.call(productId, () -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
        }
        assertThat(entered.await(5, TimeUnit.SECONDS)).as("허가를 얻었다").isTrue();
    }

    private AcceptBulkhead bulkhead(int productPermits, int instanceLimit) {
        return new AcceptBulkhead(properties(productPermits, instanceLimit), 20, registry, NO_PRODUCT_TAG);
    }

    private static AcceptBulkheadProperties properties(int productPermits, int instanceLimit) {
        return new AcceptBulkheadProperties(productPermits, 0.5, instanceLimit, SHORT_WAIT, Duration.ofSeconds(1),
                Duration.ofSeconds(3));
    }
}
