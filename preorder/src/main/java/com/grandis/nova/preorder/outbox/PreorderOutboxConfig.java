package com.grandis.nova.preorder.outbox;

import com.grandis.nova.common.outbox.OutboxDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** preorder 의 아웃박스 표와 이벤트 종류. 기록 · 발행 · 릴레이는 common:outbox 가 이 정의로 켠다. */
@Configuration(proxyBeanMethods = false)
class PreorderOutboxConfig {

    static final String TABLE = "preorder_outbox_events";

    @Bean
    OutboxDefinition preorderOutbox() {
        return new OutboxDefinition(TABLE, List.of(OutboundEventType.values()));
    }
}
