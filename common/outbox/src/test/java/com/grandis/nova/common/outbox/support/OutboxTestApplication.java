package com.grandis.nova.common.outbox.support;

import com.grandis.nova.common.outbox.OutboxDefinition;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * 아웃박스를 쓰는 서비스 흉내. 표 이름 · 종류를 OutboxDefinition 으로 주고, 업무 변경은 JPA 엔티티(BusinessRecord)로 한다.
 * 시계는 서비스처럼 저장 해상도(마이크로초)로 내린다.
 */
@SpringBootApplication
public class OutboxTestApplication {

    @Bean
    OutboxDefinition outboxDefinition() {
        return new OutboxDefinition(TestOutbox.TABLE, List.of(TestOutbox.EventType.values()));
    }

    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
