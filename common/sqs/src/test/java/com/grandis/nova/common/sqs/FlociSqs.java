package com.grandis.nova.common.sqs;

import com.grandis.nova.common.sqs.testing.FlociTestContainer;
import io.floci.testcontainers.FlociContainer;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** 싱글턴 Floci 에 붙는 클라이언트 설정. 자동설정 없이 폴러 · 전송을 직접 만드는 시험이 쓴다. */
final class FlociSqs {

    private FlociSqs() {
    }

    static SqsClientProperties properties(Map<String, String> queues) {
        FlociContainer floci = FlociTestContainer.get();
        return new SqsClientProperties(FlociTestContainer.REGION, URI.create(floci.getEndpoint()), floci.getAccessKey(),
                floci.getSecretKey(), queues, Duration.ofSeconds(3));
    }

    static String[] propertyValues() {
        FlociContainer floci = FlociTestContainer.get();
        return new String[]{
                "nova.sqs.region=" + FlociTestContainer.REGION,
                "nova.sqs.endpoint=" + floci.getEndpoint(),
                "nova.sqs.access-key=" + floci.getAccessKey(),
                "nova.sqs.secret-key=" + floci.getSecretKey()};
    }
}
