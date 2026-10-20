package com.grandis.nova.worker.sync;

import com.grandis.nova.common.sqs.BatchQueueConsumer;
import com.grandis.nova.common.sqs.DelayedResend;
import com.grandis.nova.common.sqs.SqsQueueUrls;
import com.grandis.nova.worker.config.WorkerProperties;
import com.grandis.nova.worker.job.DrawEventType;
import com.grandis.nova.worker.job.JobEventType;
import com.grandis.nova.worker.job.JobProgress;
import com.grandis.nova.worker.job.JobType;
import com.grandis.nova.worker.mock.ConcurrencyLimiter;
import com.grandis.nova.worker.mock.MockCircuit;
import com.grandis.nova.worker.mock.MockGateway;
import com.grandis.nova.worker.mock.ReceiveGate;
import com.grandis.nova.worker.mock.RejectGuards;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 작업 큐(사전예약 등록 · 취소, 럭키드로우) 소비와 결과 알림. nova.worker.consumer.enabled=false 면 통째로 만들지 않는다(큐 없이 띄울 때).
 * 켜져 있는데 SQS 설정(nova.sqs.region)이 없으면 기동이 실패한다 — 아무것도 받지 않는 worker 가 조용히 뜨지 않게.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "nova.worker.consumer", name = "enabled", matchIfMissing = true)
class SyncConfig {

    /** 드로우 메시지에서 받아 올 속성. 돌려보내기 · DLQ 에 넣을 때 그대로 잇는다. */
    private static final List<String> DRAW_ATTRIBUTE_NAMES =
            List.of("eventType", "eventId", JobProgress.MISCONFIGURED_SINCE);

    @Bean
    ResultPublisher resultPublisher(SqsClient sqs, SqsQueueUrls queueUrls, JsonMapper json, Clock clock) {
        return new ResultPublisher(sqs, queueUrls, json, clock);
    }

    @Bean
    RetrySchedule retrySchedule(WorkerProperties properties) {
        return new RetrySchedule(properties.retry(), () -> ThreadLocalRandom.current().nextDouble());
    }

    @Bean
    ShutdownSignal shutdownSignal(ApplicationContext context) {
        return new ShutdownSignal(context);
    }

    @Bean
    InRoundRetry inRoundRetry(RetrySchedule schedule, WorkerProperties properties, ShutdownSignal shutdown) {
        return new InRoundRetry(schedule, properties.retry(), shutdown);
    }

    @Bean
    DelayedResend registerResend(SqsClient sqs, SqsQueueUrls queueUrls) {
        return new DelayedResend(sqs, queueUrls, JobEventType.REGISTER_JOB_READY.queue());
    }

    @Bean
    DelayedResend cancelResend(SqsClient sqs, SqsQueueUrls queueUrls) {
        return new DelayedResend(sqs, queueUrls, JobEventType.CANCEL_JOB_READY.queue());
    }

    @Bean
    JobHandler registerJobHandler(MockGateway mock, InRoundRetry inRound, RetrySchedule schedule,
                                  ConcurrencyLimiter limiter, MockCircuit circuit, RejectGuards rejectGuards,
                                  ResultPublisher publisher, DelayedResend registerResend, WorkerProperties properties,
                                  JsonMapper json, MeterRegistry meters, Clock clock, ShutdownSignal shutdown) {
        return new JobHandler(JobType.REGISTER, new RegisterTurn(mock, inRound), schedule, limiter, circuit,
                rejectGuards.of(JobType.REGISTER), publisher, registerResend, properties, json, meters, clock, shutdown);
    }

    @Bean
    JobHandler cancelJobHandler(MockGateway mock, InRoundRetry inRound, RetrySchedule schedule,
                                ConcurrencyLimiter limiter, MockCircuit circuit, RejectGuards rejectGuards,
                                ResultPublisher publisher, DelayedResend cancelResend, WorkerProperties properties,
                                JsonMapper json, MeterRegistry meters, Clock clock, ShutdownSignal shutdown) {
        return new JobHandler(JobType.CANCEL, new CancelTurn(mock, inRound), schedule, limiter, circuit,
                rejectGuards.of(JobType.CANCEL), publisher, cancelResend, properties, json, meters, clock, shutdown);
    }

    @Bean
    DelayedResend drawResend(SqsClient sqs, SqsQueueUrls queueUrls) {
        return new DelayedResend(sqs, queueUrls, DrawEventType.QUEUE);
    }

    @Bean
    DrawHandler drawHandler(MockGateway mock, InRoundRetry inRound, ConcurrencyLimiter limiter, MockCircuit circuit,
                            RejectGuards rejectGuards, ResultPublisher publisher, DelayedResend drawResend,
                            SqsClient sqs, SqsQueueUrls queueUrls, WorkerProperties properties, JsonMapper json,
                            MeterRegistry meters, Clock clock, ShutdownSignal shutdown) {
        return new DrawHandler(mock, inRound, limiter, circuit, rejectGuards.of(JobType.DRAW), publisher, drawResend,
                new DeadLetterSender(sqs, queueUrls, DrawEventType.QUEUE), properties, json, meters, clock, shutdown);
    }

    @Bean
    InFlightJobs inFlightJobs() {
        return new InFlightJobs();
    }

    @Bean
    BatchQueueConsumer registerConsumer(SqsClient sqs, SqsQueueUrls queueUrls, WorkerProperties properties,
                                        ReceiveGate gate, JobHandler registerJobHandler, InFlightJobs inFlight,
                                        ShutdownSignal shutdown) {
        return consumer(JobEventType.REGISTER_JOB_READY, sqs, queueUrls, properties, gate, registerJobHandler,
                inFlight, shutdown);
    }

    @Bean
    BatchQueueConsumer cancelConsumer(SqsClient sqs, SqsQueueUrls queueUrls, WorkerProperties properties,
                                      ReceiveGate gate, JobHandler cancelJobHandler, InFlightJobs inFlight,
                                      ShutdownSignal shutdown) {
        return consumer(JobEventType.CANCEL_JOB_READY, sqs, queueUrls, properties, gate, cancelJobHandler, inFlight,
                shutdown);
    }

    /** 럭키드로우 응모 · 추첨 요청. 외부 호출 자리를 예약 작업과 나눠 쓴다(취소 전용 자리는 쓰지 못한다). */
    @Bean
    BatchQueueConsumer drawConsumer(SqsClient sqs, SqsQueueUrls queueUrls, WorkerProperties properties,
                                    ReceiveGate gate, DrawHandler drawHandler, InFlightJobs inFlight,
                                    ShutdownSignal shutdown) {
        BatchQueueConsumer consumer = new BatchQueueConsumer(sqs, queueUrls,
                properties.consumer().settings(DrawEventType.QUEUE), DRAW_ATTRIBUTE_NAMES,
                () -> shutdown.stopping() ? 0 : gate.receivable(JobType.DRAW, inFlight.count()), drawHandler);
        inFlight.add(consumer);
        return consumer;
    }

    /**
     * 지금 부를 수 있는 만큼만 받는다. 받은 메시지는 곧 자리를 잡으러 오고 자리는 큐들이 나눠 쓰므로,
     * 이 종류가 쓸 수 있는 자리 수에서 모든 소비기가 처리 중인 건수를 뺀다. 건수는 받기 전에 정하므로 기다리는 사이
     * 다른 큐가 자리를 쓰면 넘칠 수 있다 — 그때는 처리기가 차례를 쓰지 않고 돌려보낸다.
     * 내려가는 중에는 받지 않는다 — 받아도 돌려보낼 뿐이라, 멈출 차례를 기다리는 소비기가 받고 돌려보내기를 되풀이하지 않게.
     */
    private static BatchQueueConsumer consumer(JobEventType event, SqsClient sqs, SqsQueueUrls queueUrls,
                                               WorkerProperties properties, ReceiveGate gate, JobHandler handler,
                                               InFlightJobs inFlight, ShutdownSignal shutdown) {
        BatchQueueConsumer consumer = new BatchQueueConsumer(sqs, queueUrls,
                properties.consumer().settings(event.queue()), JobProgress.ATTRIBUTE_NAMES,
                () -> shutdown.stopping() ? 0 : gate.receivable(event.jobType(), inFlight.count()), handler);
        inFlight.add(consumer);
        return consumer;
    }
}
