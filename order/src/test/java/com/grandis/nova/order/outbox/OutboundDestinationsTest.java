package com.grandis.nova.order.outbox;

import com.grandis.nova.common.sqs.testing.FlociTestContainer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** order 가 보내는 목적지마다 시험용 Floci(곧 로컬 스크립트)에 큐가 있다. 없으면 통합 시험의 발행이 없는 큐로 가다 실패한다. */
class OutboundDestinationsTest {

    @Test
    void everyDestinationHasLocalQueue() {
        assertThat(Arrays.stream(OutboundEventType.values()).map(OutboundEventType::destination).distinct())
                .allMatch(FlociTestContainer.QUEUES::contains, "FlociTestContainer.QUEUES 에 있음");
    }
}
