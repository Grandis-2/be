package com.grandis.nova.catalog.integration;

import com.grandis.nova.catalog.listing.CampaignWindow;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("preorder 회차 일정 — /open 은 5초 쓰고, 답하지 않으면 마지막 값을 10분까지, 상품별은 100개씩 캐시 없이")
class PreorderCampaignsTest {

    static final Instant NOW = Instant.parse("2026-10-20T01:00:00Z");
    static final Duration REFRESH = Duration.ofSeconds(5);

    final MutableClock clock = new MutableClock(NOW);
    final FakeClient client = new FakeClient();
    final PreorderCampaigns campaigns = new PreorderCampaigns(client, clock, REFRESH);

    @Test
    @DisplayName("/open 은 받은 뒤 5초 동안 다시 묻지 않고, 5초가 되면 다시 묻는다")
    void openIsReusedForRefreshInterval() {
        UUID product = UUID.randomUUID();
        client.open = () -> open(product);

        assertThat(campaigns.open()).containsOnlyKeys(product);
        clock.advance(REFRESH.minusNanos(1));
        campaigns.open();
        assertThat(client.openCalls).hasValue(1);
        clock.advance(Duration.ofNanos(1));
        campaigns.open();
        assertThat(client.openCalls).hasValue(2);
    }

    @Test
    @DisplayName("preorder 가 답하지 않으면 마지막 값을 받은 지 10분까지 쓰고, 그 뒤엔 빈 값이다 — 실패 뒤 5초는 다시 묻지 않는다")
    void staleOpenIsServedUpToTenMinutes() {
        UUID product = UUID.randomUUID();
        client.open = () -> open(product);
        campaigns.open();
        Instant fetchedAt = clock.instant();
        client.open = () -> {
            throw new ResourceAccessException("preorder down");
        };

        clock.advance(REFRESH);
        assertThat(campaigns.open()).as("실패 — 마지막 값").containsOnlyKeys(product);
        int calls = client.openCalls.get();
        clock.advance(REFRESH.minusNanos(1));
        assertThat(campaigns.open()).containsOnlyKeys(product);
        assertThat(client.openCalls).as("실패 뒤 5초 안에는 다시 묻지 않는다").hasValue(calls);

        clock.set(fetchedAt.plus(PreorderCampaigns.MAX_STALE));
        assertThat(campaigns.open()).as("받은 지 정확히 10분 — 아직 쓴다").containsOnlyKeys(product);
        clock.set(fetchedAt.plus(PreorderCampaigns.MAX_STALE).plusNanos(1));
        assertThat(campaigns.open()).as("10분을 넘으면 빈 값(목록에서 사전예약을 뺀다)").isEmpty();

        client.open = () -> open(product);
        clock.advance(REFRESH);
        assertThat(campaigns.open()).as("다시 답하면 새 값").containsOnlyKeys(product);
    }

    @Test
    @DisplayName("받은 적이 없는데 preorder 가 답하지 않으면 빈 값이다")
    void neverFetchedAndDownIsEmpty() {
        client.open = () -> {
            throw new ResourceAccessException("preorder down");
        };

        assertThat(campaigns.open()).isEmpty();
    }

    @Test
    @DisplayName("/open 이 계약과 다르면(items 없음 · 같은 상품 두 번 · 빈 시각) 연동 오류로 보고 마지막 값을 쓴다 — 빈 목록은 회차 없음")
    void openContractViolationKeepsLastValue() {
        UUID product = UUID.randomUUID();
        client.open = () -> open(product);
        campaigns.open();

        for (Supplier<ApiResponse<PreorderCampaignClient.OpenCampaigns>> broken : List.<Supplier<ApiResponse<PreorderCampaignClient.OpenCampaigns>>>of(
                () -> ApiResponse.ok(new PreorderCampaignClient.OpenCampaigns(null, NOW)),
                () -> ApiResponse.ok(new PreorderCampaignClient.OpenCampaigns(List.of(campaign(product), campaign(product)), NOW)),
                () -> ApiResponse.ok(new PreorderCampaignClient.OpenCampaigns(List.of(new PreorderCampaignClient.Campaign(product, null, NOW)), NOW)))) {
            client.open = broken;
            clock.advance(REFRESH);
            assertThat(campaigns.open()).containsOnlyKeys(product);
        }
        client.open = () -> ApiResponse.ok(new PreorderCampaignClient.OpenCampaigns(List.of(), NOW));
        clock.advance(REFRESH);
        assertThat(campaigns.open()).as("대조군 — 빈 목록은 열린 회차 없음").isEmpty();
    }

    @Test
    @DisplayName("쓸 값이 없을 때(첫 조회 · 마지막 값이 10분을 넘음) 같이 들어온 요청은 받는 쪽을 기다려 그 값을 쓴다 — 빈 값으로 사전예약을 빼지 않고, preorder 는 한 번만 부른다")
    void concurrentCallersWaitWhenNothingUsable() throws Exception {
        UUID product = UUID.randomUUID();
        for (boolean expired : new boolean[]{false, true}) {
            if (expired) {
                client.open = () -> open(product);
                clock.advance(PreorderCampaigns.MAX_STALE.plusNanos(1));   // 앞 바퀴에 받은 값이 10분을 넘었다
            }
            int callsBefore = client.openCalls.get();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            client.open = () -> {
                entered.countDown();
                await(release);
                return open(product);
            };
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Map<UUID, CampaignWindow>> first = pool.submit(campaigns::open);
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                Future<Map<UUID, CampaignWindow>> second = pool.submit(campaigns::open);
                Thread.sleep(200);
                assertThat(second.isDone()).as("%s — 쓸 값이 없으면 기다린다", expired ? "10분 넘음" : "첫 조회").isFalse();
                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).containsOnlyKeys(product);
                assertThat(second.get(5, TimeUnit.SECONDS)).as("%s — 받는 쪽의 값", expired ? "10분 넘음" : "첫 조회").containsOnlyKeys(product);
                assertThat(client.openCalls.get() - callsBefore).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("쓸 수 있는 마지막 값이 있으면 같이 들어온 요청은 새로 받는 쪽을 기다리지 않는다")
    void concurrentCallersUseLastValueWhileRefreshing() throws Exception {
        UUID product = UUID.randomUUID();
        client.open = () -> open(product);
        campaigns.open();
        clock.advance(REFRESH);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        client.open = () -> {
            entered.countDown();
            await(release);
            return open(product);
        };
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<Map<UUID, CampaignWindow>> refreshing = pool.submit(campaigns::open);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(campaigns.open()).as("새로 받는 중 — 마지막 값을 바로 쓴다").containsOnlyKeys(product);
            release.countDown();
            refreshing.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("상품별 회차는 100개씩 나눠 묻고 캐시하지 않는다 — 250개면 100 · 100 · 50, 같은 id 는 한 번")
    void findAsksInBatchesOfHundred() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            ids.add(UUID.randomUUID());
        }
        ids.add(ids.getFirst());
        client.campaigns = asked -> asked.stream().map(PreorderCampaignsTest::campaign).toList();

        Map<UUID, CampaignWindow> found = campaigns.find(ids);

        assertThat(found).hasSize(250);
        assertThat(client.asked).extracting(List::size).containsExactly(100, 100, 50);
        campaigns.find(ids.getFirst());
        assertThat(client.asked).as("캐시하지 않는다").hasSize(4);
        assertThat(campaigns.find(List.of())).isEmpty();
        assertThat(client.asked).as("빈 목록이면 부르지 않는다").hasSize(4);
    }

    @Test
    @DisplayName("상품별 회차가 계약과 다르면(items 없음 · 묻지 않은 상품 · 같은 상품 두 번 · 빈 시각) 연동 오류(500), preorder 장애는 503")
    void findContractViolations() {
        UUID asked = UUID.randomUUID();
        List<java.util.function.Function<List<UUID>, List<PreorderCampaignClient.Campaign>>> broken = List.of(
                ids -> null,
                ids -> List.of(campaign(UUID.randomUUID())),
                ids -> List.of(campaign(asked), campaign(asked)),
                ids -> List.of(new PreorderCampaignClient.Campaign(asked, NOW, null)));
        for (var answer : broken) {
            client.campaigns = answer;
            assertThatThrownBy(() -> campaigns.find(asked)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.INTERNAL_ERROR));
        }
        client.campaigns = ids -> List.of();
        assertThat(campaigns.find(asked)).as("대조군 — 빈 목록은 회차 없음(판매 준비 전)").isEmpty();
        client.campaigns = ids -> {
            throw new ResourceAccessException("preorder down");
        };
        assertThatThrownBy(() -> campaigns.find(asked)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    private static ApiResponse<PreorderCampaignClient.OpenCampaigns> open(UUID product) {
        return ApiResponse.ok(new PreorderCampaignClient.OpenCampaigns(List.of(campaign(product)), NOW));
    }

    private static PreorderCampaignClient.Campaign campaign(UUID product) {
        return new PreorderCampaignClient.Campaign(product, NOW, NOW.plus(Duration.ofDays(1)));
    }

    static final class FakeClient implements PreorderCampaignClient {

        final AtomicInteger openCalls = new AtomicInteger();
        final List<List<UUID>> asked = new ArrayList<>();
        Supplier<ApiResponse<OpenCampaigns>> open;
        java.util.function.Function<List<UUID>, List<Campaign>> campaigns;

        @Override
        public ApiResponse<OpenCampaigns> getOpen() {
            openCalls.incrementAndGet();
            return open.get();
        }

        @Override
        public ApiResponse<Campaigns> getCampaigns(Collection<UUID> productIds) {
            List<UUID> ids = List.copyOf(productIds);
            asked.add(ids);
            return ApiResponse.ok(new Campaigns(campaigns.apply(ids)));
        }
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
