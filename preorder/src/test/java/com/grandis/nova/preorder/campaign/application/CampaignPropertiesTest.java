package com.grandis.nova.preorder.campaign.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 접수 중 회차 내부 API 의 상한은 1 ~ 10,000 이다 — 조회가 상한 + 1 건을 읽으므로 넘침도 막는다. */
class CampaignPropertiesTest {

    static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    @Test
    void 상한은_1부터_10000까지다() {
        assertThatCode(() -> properties(1)).doesNotThrowAnyException();
        assertThatCode(() -> properties(CampaignProperties.MAX_INTERNAL_OPEN_LIMIT)).doesNotThrowAnyException();
        assertThatThrownBy(() -> properties(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(CampaignProperties.MAX_INTERNAL_OPEN_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Integer.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal-open-limit");
    }

    private static CampaignProperties properties(int internalOpenLimit) {
        return new CampaignProperties(TEN_MINUTES, TEN_MINUTES, internalOpenLimit);
    }
}
