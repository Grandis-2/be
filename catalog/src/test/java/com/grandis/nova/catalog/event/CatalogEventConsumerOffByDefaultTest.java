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
 * 기본 설정에는 소비기가 없다 — 큐(인프라)가 생기기 전에 배포돼도 catalog-events 를 찾지 않는다. 분배기는 처리기 없이도 뜬다.
 * 운영처럼 SQS 지역은 준다 — 없으면 소비기가 켜지는 고장에서도 단언이 아니라 SqsClient 빈이 없어 컨텍스트가 실패해 시험이 고장을 가린다.
 */
@CatalogIntegrationTest
@TestPropertySource(properties = "nova.sqs.region=" + FlociTestContainer.REGION)
@Import(SqsTestConfig.class)
@DisplayName("catalog-events 소비기는 기본으로 꺼져 있다")
class CatalogEventConsumerOffByDefaultTest {

    @Autowired ApplicationContext context;

    @Test
    @DisplayName("소비기 빈이 없고, 분배기는 처리기 없이 떠 있다")
    void noConsumerByDefault() {
        assertThat(context.getBeansOfType(RetryingQueueConsumer.class)).isEmpty();
        assertThat(context.getBeansOfType(CatalogEventDispatcher.class)).hasSize(1);
        assertThat(context.getBeansOfType(CatalogEventHandler.class)).isEmpty();
    }
}
