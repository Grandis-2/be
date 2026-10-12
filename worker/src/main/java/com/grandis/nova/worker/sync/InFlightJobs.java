package com.grandis.nova.worker.sync;

import com.grandis.nova.common.sqs.BatchQueueConsumer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 모든 작업 큐의 소비기가 받아서 아직 처리 중인 건수. 외부를 부르는 자리는 큐들이 나눠 쓰므로 합쳐서 센다. */
final class InFlightJobs {

    private final List<BatchQueueConsumer> consumers = new CopyOnWriteArrayList<>();

    void add(BatchQueueConsumer consumer) {
        consumers.add(consumer);
    }

    int count() {
        int count = 0;
        for (BatchQueueConsumer consumer : consumers) {
            count += consumer.inFlight();
        }
        return count;
    }
}
