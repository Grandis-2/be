package com.grandis.nova.worker.sync;

import com.grandis.nova.common.sqs.BatchQueueConsumer;
import com.grandis.nova.common.sqs.DelayedResend;
import com.grandis.nova.common.sqs.SqsQueueUrls;
import com.grandis.nova.worker.config.WorkerProperties;
import com.grandis.nova.worker.job.JobEventType;
import com.grandis.nova.worker.job.JobProgress;
import com.grandis.nova.worker.job.JobType;
import com.grandis.nova.worker.mock.ConcurrencyLimiter;
import com.grandis.nova.worker.mock.MockCircuit;
import com.grandis.nova.worker.mock.MockGateway;
import com.grandis.nova.worker.mock.ReceiveGate;
import com.grandis.nova.worker.mock.RejectGuard;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 작업 큐 소비와 결과 알림. nova.worker.consumer.enabled=false 면 통째로 만들지 않는다(큐 없이 띄울 때).
 * 켜져 있는데 SQS 설정(nova.sqs.region)이 없으면 기동이 실패한다 — 아무것도 받지 않는 worker 가 조용히 뜨지 않게.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "nova.worker.consumer", name = "enabled", matchIfMissing = true)
class SyncConfig {

    @Bean
    ResultPublisher resultPublisher(SqsClient sqs, SqsQueueUrls queueUrls, JsonMapper json, Clock clock) {
        return new ResultPublisher(sqs, queueUrls, json, clock);
    }

    @Bean
    RetrySchedule retrySchedule(WorkerProperties properties) {
        return new RetrySchedule(properties.retry(), () -> ThreadLocalRandom.current().nextDouble());
    }

    @Bean
    JobHandler registerJobHandler(MockGateway mock, RetrySchedule schedule, ConcurrencyLimiter limiter,
                                  MockCircuit circuit, RejectGuard rejectGuard, ResultPublisher publisher,
                                  SqsClient sqs, SqsQueueUrls queueUrls, WorkerProperties properties, JsonMapper json,
                                  MeterRegistry meters, Clock clock) {
        return new JobHandler(JobType.REGISTER, new RegisterTurn(mock, schedule, properties.retry()), schedule, limiter,
                circuit, rejectGuard, publisher,
                new DelayedResend(sqs, queueUrls, JobEventType.REGISTER_JOB_READY.queue()), properties, json, meters,
                clock);
    }

    /** 등록 큐 소비기. 지금 부를 수 있는 만큼만 받는다 — 받은 메시지는 곧 자리를 잡으러 오므로, 처리 중인 건수를 뺀 자리 수다. */
    @Bean
    BatchQueueConsumer registerConsumer(SqsClient sqs, SqsQueueUrls queueUrls, WorkerProperties properties,
                                        ReceiveGate gate, JobHandler registerJobHandler) {
        AtomicReference<BatchQueueConsumer> self = new AtomicReference<>();
        BatchQueueConsumer consumer = new BatchQueueConsumer(sqs, queueUrls,
                properties.consumer().settings(JobEventType.REGISTER_JOB_READY.queue()), JobProgress.ATTRIBUTE_NAMES,
                () -> gate.receivable(JobType.REGISTER, self.get().inFlight()), registerJobHandler);
        self.set(consumer);
        return consumer;
    }
}
