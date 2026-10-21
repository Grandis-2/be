package com.grandis.nova.preorder.scaling;

import com.grandis.nova.preorder.campaign.Campaigns;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;

import java.time.Clock;

/** 켜면 AWS 에 넣고, 꺼져 있으면 계산만 해 로그로 남긴다. 자격 증명은 태스크 역할(기본 공급자 체인)이다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScalingProperties.class)
class ScalingConfig {

    @Bean
    CampaignScalingReconciler campaignScalingReconciler(Campaigns campaigns, ScalingProperties properties,
                                                        CapacityFloors floors, Clock clock,
                                                        MeterRegistry meterRegistry) {
        return new CampaignScalingReconciler(campaigns, properties, floors, clock, meterRegistry);
    }

    @Bean
    @ConditionalOnBooleanProperty("nova.scaling.enabled")
    ApplicationAutoScalingClient applicationAutoScalingClient(ScalingProperties properties) {
        return ApplicationAutoScalingClient.builder().region(Region.of(properties.region())).build();
    }

    @Bean
    @ConditionalOnBooleanProperty("nova.scaling.enabled")
    CapacityFloors awsCapacityFloors(ApplicationAutoScalingClient client, ScalingProperties properties) {
        return new AwsCapacityFloors(client, properties.cluster());
    }

    @Bean
    @ConditionalOnBooleanProperty(name = "nova.scaling.enabled", havingValue = false, matchIfMissing = true)
    CapacityFloors loggingCapacityFloors() {
        return new LoggingCapacityFloors();
    }
}
