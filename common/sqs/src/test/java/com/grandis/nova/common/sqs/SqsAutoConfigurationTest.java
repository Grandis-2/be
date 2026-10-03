package com.grandis.nova.common.sqs;

import com.grandis.nova.common.outbox.MessageTransport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 클라이언트는 지역이 있을 때만, 아웃박스 전송은 아웃박스가 있고 transport=sqs 일 때만 켜진다. */
class SqsAutoConfigurationTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SqsAutoConfiguration.class));

    final ApplicationContextRunner withRegion = runner.withPropertyValues(
            "nova.sqs.region=ap-northeast-2", "nova.sqs.endpoint=http://localhost:4566");

    @Test
    void 지역이_없으면_클라이언트를_만들지_않는다() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(SqsClient.class);
            assertThat(ctx).doesNotHaveBean(SqsQueueUrls.class);
        });
    }

    @Test
    void 지역이_있으면_클라이언트와_큐_URL_을_만든다() {
        withRegion.run(ctx -> {
            assertThat(ctx).hasSingleBean(SqsClient.class);
            assertThat(ctx).hasSingleBean(SqsQueueUrls.class);
            assertThat(ctx).doesNotHaveBean(MessageTransport.class);
        });
    }

    @Test
    void transport_가_sqs_면_아웃박스_전송을_만든다() {
        withRegion.withPropertyValues("nova.outbox.transport=sqs").run(ctx ->
                assertThat(ctx.getBean(MessageTransport.class)).isInstanceOf(SqsMessageTransport.class));
    }

    @Test
    void transport_가_sqs_가_아니면_아웃박스_전송을_만들지_않는다() {
        withRegion.withPropertyValues("nova.outbox.transport=log").run(ctx ->
                assertThat(ctx).doesNotHaveBean(MessageTransport.class));
    }

    /** SQS 로 보내기만 하는 서비스(batch)는 아웃박스를 의존하지 않는다. 그래도 transport 설정이 남아 있으면 무시하고 뜬다. */
    @Test
    void 아웃박스가_클래스패스에_없으면_SQS_만으로_뜬다() {
        withRegion.withPropertyValues("nova.outbox.transport=sqs")
                .withClassLoader(new FilteredClassLoader("com.grandis.nova.common.outbox"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(SqsClient.class);
                    assertThat(ctx).hasSingleBean(SqsQueueUrls.class);
                    assertThat(ctx).doesNotHaveBean(SqsMessageTransport.class);
                });
    }

    @Test
    void transport_가_sqs_인데_지역이_없으면_기동이_실패한다() {
        runner.withPropertyValues("nova.outbox.transport=sqs").run(ctx -> assertThat(ctx).hasFailed());
    }

    /** 처음 보내는 목적지는 큐 URL 조회와 전송을 차례로 부른다. 릴레이 리스는 이 시간보다 길어야 한다(common:outbox 가 본다). */
    @Test
    void 전송_한_번의_최대_시간은_호출_제한_시간의_두_배다() {
        withRegion.withPropertyValues("nova.outbox.transport=sqs", "nova.sqs.api-call-timeout=4s").run(ctx ->
                assertThat(ctx.getBean(MessageTransport.class).maxSendTime()).isEqualTo(Duration.ofSeconds(8)));
    }

    /** 서비스가 자기 클라이언트를 두면 자동설정은 물러난다. */
    @Test
    void 서비스가_클라이언트를_두면_만들지_않는다() {
        SqsClient own = SqsClient.builder().region(Region.AP_NORTHEAST_2).build();
        withRegion.withBean(SqsClient.class, () -> own).run(ctx ->
                assertThat(ctx.getBean(SqsClient.class)).isSameAs(own));
        own.close();
    }
}
