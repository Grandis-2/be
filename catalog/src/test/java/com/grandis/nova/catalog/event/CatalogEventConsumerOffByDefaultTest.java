package com.grandis.nova.catalog.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.common.sqs.RetryingQueueConsumer;
import com.grandis.nova.common.sqs.testing.FlociTestContainer;
import com.grandis.nova.common.sqs.testing.SqsTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * 설정으로 끄면(nova.sqs.consumer.enabled=false — 시험 기본값) 소비기가 없다. 운영 기본값은 켬이다(CatalogDefaults). 분배기와 처리기는 꺼도 뜬다.
 * 운영처럼 SQS 지역은 준다 — 없으면 소비기가 켜지는 고장에서도 단언이 아니라 SqsClient 빈이 없어 컨텍스트가 실패해 시험이 고장을 가린다.
 */
@CatalogIntegrationTest
@TestPropertySource(properties = "nova.sqs.region=" + FlociTestContainer.REGION)
@Import(SqsTestConfig.class)
@DisplayName("catalog-events 소비기는 끄면 없다")
class CatalogEventConsumerOffByDefaultTest {

    @Autowired ApplicationContext context;

    @Test
    @DisplayName("소비기 빈이 없고, 분배기와 판매 준비 처리기는 떠 있다")
    void noConsumerWhenDisabled() {
        assertThat(context.getBeansOfType(RetryingQueueConsumer.class)).isEmpty();
        assertThat(context.getBeansOfType(CatalogEventDispatcher.class)).hasSize(1);
        assertThat(context.getBeansOfType(CatalogEventHandler.class).values()).extracting(CatalogEventHandler::eventType)
                .containsExactly(InStockProductReadyHandler.EVENT_TYPE);
    }
}
