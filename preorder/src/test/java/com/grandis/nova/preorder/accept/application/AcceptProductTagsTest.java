package com.grandis.nova.preorder.accept.application;

import com.grandis.nova.preorder.campaign.Campaigns;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class AcceptProductTagsTest {

    static final UUID PRODUCT_A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    static final UUID PRODUCT_B = UUID.fromString("00000000-0000-7000-8000-00000000000b");

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Campaigns campaigns;
    AcceptProductTags tags;

    @BeforeEach
    void setUp() {
        campaigns = mock(Campaigns.class);
        tags = new AcceptProductTags(campaigns, registry,
                Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void 접수_중인_회차만_상품_id_이고_나머지는_other() {
        accepting(PRODUCT_A);

        assertThat(tags.of(PRODUCT_A)).isEqualTo(PRODUCT_A.toString());
        assertThat(tags.of(PRODUCT_B)).isEqualTo(AcceptProductTags.OTHER);
    }

    @Test
    void 새로_접수를_시작한_상품은_업무_지표를_0_부터_낸다() {
        accepting(PRODUCT_A);

        assertThat(registry.find(PreorderAcceptService.CREATED_METRIC)
                .tag(AcceptProductTags.PRODUCT_TAG, PRODUCT_A.toString()).counters()).hasSize(6)
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    void 접수를_벗어난_상품의_시계열은_한_번_더_갱신한_뒤_지운다() {
        accepting(PRODUCT_A);
        registry.counter(AcceptBulkhead.REJECTED_METRIC, "limit", "product",
                AcceptProductTags.PRODUCT_TAG, PRODUCT_A.toString()).increment();

        accepting();
        assertThat(tags.of(PRODUCT_A)).isEqualTo(AcceptProductTags.OTHER);
        assertThat(productMeters(PRODUCT_A)).as("마지막 증가분이 수집되게 한 주기 둔다").isEqualTo(7);

        accepting();
        assertThat(productMeters(PRODUCT_A)).isZero();
    }

    @Test
    void 벗어났다가_바로_돌아온_상품은_지우지_않는다() {
        accepting(PRODUCT_A);
        accepting();
        accepting(PRODUCT_A);
        accepting(PRODUCT_A);

        assertThat(tags.of(PRODUCT_A)).isEqualTo(PRODUCT_A.toString());
        assertThat(productMeters(PRODUCT_A)).isEqualTo(6);
    }

    private void accepting(UUID... productIds) {
        given(campaigns.findAcceptingProductIds(any())).willReturn(Set.of(productIds));
        tags.refresh();
    }

    private long productMeters(UUID productId) {
        return registry.getMeters().stream()
                .filter(meter -> productId.toString().equals(meter.getId().getTag(AcceptProductTags.PRODUCT_TAG)))
                .count();
    }
}
