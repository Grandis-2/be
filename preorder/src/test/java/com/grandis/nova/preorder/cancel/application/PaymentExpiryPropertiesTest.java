package com.grandis.nova.preorder.cancel.application;

import com.grandis.nova.preorder.cancel.ExpiryCancelService;
import com.grandis.nova.preorder.preorder.Preorders;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PaymentExpiryPropertiesTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentExpiryConfig.class, PaymentExpiryJob.class)
            .withBean(Preorders.class, () -> mock(Preorders.class))
            .withBean(ExpiryCancelService.class, () -> mock(ExpiryCancelService.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void 기본값은_꺼져_있고_1분마다_100건이며_꺼져_있으면_작업을_만들지_않는다() {
        runner.run(context -> {
            PaymentExpiryProperties properties = context.getBean(PaymentExpiryProperties.class);

            assertThat(properties.enabled()).isFalse();
            assertThat(properties.interval()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.batchSize()).isEqualTo(100);
            assertThat(context).doesNotHaveBean(PaymentExpiryJob.class);
        });
    }

    @Test
    void 켜면_작업을_만든다() {
        runner.withPropertyValues("nova.preorder-expiry.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(PaymentExpiryJob.class));
    }

    /** 꺼져 있어도 값을 검사한다 — 켜는 날 처음 드러나지 않게. */
    @Test
    void 범위_밖의_값이면_꺼져_있어도_기동을_막는다() {
        for (String value : new String[] {"nova.preorder-expiry.interval=9s", "nova.preorder-expiry.interval=61m",
                "nova.preorder-expiry.batch-size=0", "nova.preorder-expiry.batch-size=1001"}) {
            runner.withPropertyValues(value).run(context -> assertThat(context).as(value).hasFailed());
        }
    }
}
