package com.grandis.nova.preorder.campaign.application;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 접두어가 회차 설정(nova.campaign) 아래에 있어, 두 설정이 서로를 깨지 않고 묶이는지 본다. */
class CampaignProgressPropertiesTest {

    @Test
    void 설정하지_않으면_5초_200개다() {
        CampaignProgressProperties properties = binder(Map.of())
                .bindOrCreate("nova.campaign.progress", CampaignProgressProperties.class);

        assertThat(properties.cacheTtl()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.cacheMaxProducts()).isEqualTo(200);
    }

    @Test
    void 설정한_값을_쓰고_회차_설정은_그대로_묶인다() {
        Binder binder = binder(Map.of("nova.campaign.progress.cache-ttl", "0s",
                "nova.campaign.progress.cache-max-products", "10", "nova.campaign.min-lead-time", "3m"));

        CampaignProgressProperties progress = binder.bind("nova.campaign.progress", CampaignProgressProperties.class)
                .get();
        CampaignProperties campaign = binder.bind("nova.campaign", CampaignProperties.class).get();

        assertThat(progress.cacheTtl()).isZero();
        assertThat(progress.cacheMaxProducts()).isEqualTo(10);
        assertThat(campaign.minLeadTime()).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void 음수_시간과_0_개는_받지_않는다() {
        assertThatThrownBy(() -> binder(Map.of("nova.campaign.progress.cache-ttl", "-1s"))
                .bind("nova.campaign.progress", CampaignProgressProperties.class)).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> binder(Map.of("nova.campaign.progress.cache-max-products", "0"))
                .bind("nova.campaign.progress", CampaignProgressProperties.class)).isInstanceOf(BindException.class);
    }

    private static Binder binder(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties));
    }
}
