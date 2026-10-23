package com.grandis.nova.preorder.scaling;

import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.capacity.CapacityHeartbeatProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;

import java.time.Clock;
import java.time.Duration;

/** 켜면 AWS 에 넣고, 꺼져 있으면 계산만 해 로그로 남긴다. 자격 증명은 태스크 역할(기본 공급자 체인)이다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScalingProperties.class)
class ScalingConfig {

    static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(5);
    static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(2);

    @Bean
    CampaignScalingReconciler campaignScalingReconciler(Campaigns campaigns, ScalingProperties properties,
                                                        CapacityFloors floors, StringRedisTemplate redis,
                                                        CapacityHeartbeatProperties capacity, Clock clock,
                                                        MeterRegistry meterRegistry) {
        return new CampaignScalingReconciler(campaigns, properties, floors,
                new ScalingLock(redis, properties.lockTtl()), new AdmissionCredit(redis), capacity.acceptPerSecond(),
                clock, meterRegistry);
    }

    @Bean
    @ConditionalOnBooleanProperty("nova.scaling.enabled")
    ApplicationAutoScalingClient applicationAutoScalingClient(ScalingProperties properties) {
        // 늦으면 끊는다 — 실패는 다음 주기에 다시 맞추면 되고, 맞추기 한 번(호출 넷)이 잠금 수명 안에 끝나야 한다
        return ApplicationAutoScalingClient.builder()
                .region(Region.of(properties.region()))
                .overrideConfiguration(config -> config.apiCallTimeout(API_CALL_TIMEOUT)
                        .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT))
                .build();
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
