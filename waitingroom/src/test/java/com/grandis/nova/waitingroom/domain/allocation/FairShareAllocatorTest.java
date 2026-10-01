package com.grandis.nova.waitingroom.domain.allocation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FairShareAllocatorTest {

    static final long NO_CAP = Long.MAX_VALUE;

    private final FairShareAllocator allocator = new FairShareAllocator();

    private Map<String, Long> allocate(long globalCredit, ProductDemand... demands) {
        return allocator.allocate(globalCredit, List.of(demands), 0).stream()
                .collect(Collectors.toMap(Grant::productKey, Grant::credit));
    }

    @Test
    void 몰리는_모델이_있어도_다른_모델이_굶지_않는다() {
        Map<String, Long> grants = allocate(100, new ProductDemand("hot", 10_000, NO_CAP, true),
                new ProductDemand("warm", 10_000, NO_CAP, true));

        assertThat(grants).containsEntry("hot", 50L).containsEntry("warm", 50L);
    }

    @Test
    void 덜_원하는_모델이_남긴_몫은_굶주린_모델로_간다() {
        Map<String, Long> grants = allocate(100, new ProductDemand("small", 10, NO_CAP, true),
                new ProductDemand("big", 10_000, NO_CAP, true));

        assertThat(grants).containsEntry("small", 10L).containsEntry("big", 90L);
    }

    @Test
    void 모델_상한이_천장이다() {
        Map<String, Long> grants = allocate(100, new ProductDemand("capped", 10_000, 30, true),
                new ProductDemand("open", 10_000, NO_CAP, true));

        assertThat(grants).containsEntry("capped", 30L).containsEntry("open", 70L);
    }

    @Test
    void 줄이_없는_모델은_몫을_안_받고_나머지는_틱마다_돌아가며_한_명씩_준다() {
        List<ProductDemand> demands = List.of(new ProductDemand("idle", 0, NO_CAP, true),
                new ProductDemand("a", 100, NO_CAP, true), new ProductDemand("b", 100, NO_CAP, true), new ProductDemand("c", 100, NO_CAP, true));

        Map<String, Long> first = allocator.allocate(10, demands, 0).stream().collect(Collectors.toMap(Grant::productKey, Grant::credit));
        Map<String, Long> next = allocator.allocate(10, demands, 1).stream().collect(Collectors.toMap(Grant::productKey, Grant::credit));

        assertThat(first).doesNotContainKey("idle").containsEntry("a", 4L).containsEntry("b", 3L).containsEntry("c", 3L);
        assertThat(next).containsEntry("a", 3L).containsEntry("b", 4L).containsEntry("c", 3L);
    }

    @Test
    void 나머지는_굶주린_모델끼리만_고르게_돌고_넘겨받은_순서와_무관하다() {
        List<ProductDemand> demands = List.of(new ProductDemand("b", 100, NO_CAP, true),
                new ProductDemand("a", 1, NO_CAP, true), new ProductDemand("c", 100, NO_CAP, true));
        Map<String, Long> received = new HashMap<>();
        for (int tick = 0; tick < 4; tick++) {
            allocator.allocate(4, demands, tick).forEach(grant -> received.merge(grant.productKey(), grant.credit(), Long::sum));
        }

        assertThat(received).containsEntry("a", 4L).containsEntry("b", 6L).containsEntry("c", 6L);
        assertThat(allocator.allocate(4, List.of(demands.get(2), demands.get(0), demands.get(1)), 1))
                .as("순서를 바꿔 넘겨도 같은 배분").containsExactlyInAnyOrderElementsOf(allocator.allocate(4, demands, 1));
    }

    @Test
    void 같은_모델의_요구가_둘이면_막는다() {
        assertThatThrownBy(() -> allocator.allocate(10, List.of(new ProductDemand("a", 1, NO_CAP, true),
                new ProductDemand("a", 2, NO_CAP, true)), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 모델이_전역_속도보다_많아도_모두_0_이_되지_않는다() {
        List<ProductDemand> demands = List.of(new ProductDemand("a", 9, NO_CAP, true), new ProductDemand("b", 9, NO_CAP, true),
                new ProductDemand("c", 9, NO_CAP, true));

        assertThat(allocator.allocate(2, demands, 0).stream().mapToLong(Grant::credit).sum()).isEqualTo(2);
    }

    @Test
    void 접수_중이_아닌_모델은_줄이_있어도_몫이_0_이다() {
        Map<String, Long> grants = allocate(100, new ProductDemand("closed", 10_000, NO_CAP, false),
                new ProductDemand("open", 10_000, NO_CAP, true));

        assertThat(grants).doesNotContainKey("closed").containsEntry("open", 100L);
    }

    @Test
    void 아무렇게나_나눠도_총합은_전역_속도와_요구량_중_작은_값과_같다() {
        Random random = new Random(7);
        for (int round = 0; round < 500; round++) {
            long global = random.nextInt(1_000);
            int count = 1 + random.nextInt(8);
            List<ProductDemand> demands = IntStream.range(0, count).boxed()
                    .map(index -> new ProductDemand("p" + index, random.nextInt(300),
                            random.nextBoolean() ? NO_CAP : 1 + random.nextInt(100), random.nextInt(5) > 0))
                    .toList();
            List<Grant> grants = allocator.allocate(global, demands, round);
            long total = grants.stream().mapToLong(Grant::credit).sum();
            long wanted = demands.stream().mapToLong(ProductDemand::want).sum();

            assertThat(total).isEqualTo(Math.min(global, wanted));
            assertThat(grants).allSatisfy(grant -> assertThat(grant.credit()).isLessThanOrEqualTo(
                    demands.stream().filter(d -> d.productKey().equals(grant.productKey())).mapToLong(ProductDemand::want).max().orElseThrow()));
        }
    }

    @Test
    void 잘못된_요구는_만들지_않는다() {
        assertThatThrownBy(() -> new ProductDemand(" ", 1, 1, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductDemand("p", -1, 1, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductDemand("p", 1, 0, true)).isInstanceOf(IllegalArgumentException.class);
    }
}
