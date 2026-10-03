package com.grandis.nova.waitingroom.schedule;

import com.grandis.nova.common.sqs.RetryingQueueConsumer;
import com.grandis.nova.common.sqs.SqsQueueUrls;
import com.grandis.nova.waitingroom.control.ScheduleResyncRequester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * SQS 가 있으면(nova.sqs.region) 일정 이벤트를 받고 재발행을 요청한다. 없으면(로컬) 받지 않고, 재발행 요청은
 * 로그로만 남긴다 — 일정은 그때 Redis 에 직접 넣는다.
 */
@Configuration(proxyBeanMethods = false)
class ScheduleMessagingConfig {

    private static final Logger log = LoggerFactory.getLogger(ScheduleMessagingConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "nova.sqs", name = "region")
    RetryingQueueConsumer campaignScheduleConsumer(SqsClient sqs, SqsQueueUrls queueUrls, ScheduleProperties properties,
                                                   CampaignScheduleHandler handler) {
        return new RetryingQueueConsumer(sqs, queueUrls, properties.poller(), handler);
    }

    @Bean
    ScheduleResyncRequester scheduleResyncRequester(ObjectProvider<SqsClient> sqs, ObjectProvider<SqsQueueUrls> queueUrls,
                                                    ScheduleProperties properties, JsonMapper jsonMapper, Clock clock) {
        SqsClient client = sqs.getIfAvailable();
        if (client == null) {
            return reason -> Mono.fromRunnable(() -> log.warn("SQS 가 없어 회차 일정 재발행을 요청하지 못한다 — 일정을 직접 넣어야 한다"));
        }
        return new SqsScheduleResyncRequester(client, queueUrls.getObject(), properties.resyncQueue(), jsonMapper, clock);
    }
}
