package com.grandis.nova.catalog.outbox;

import com.grandis.nova.common.outbox.OutboxDefinition;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** catalog 의 아웃박스 표와 이벤트 종류. 기록 · 발행 · 릴레이는 common:outbox 가 이 정의로 켠다. */
@Configuration(proxyBeanMethods = false)
class CatalogOutboxConfig {

    static final String TABLE = "catalog_outbox_events";

    @Bean
    OutboxDefinition catalogOutbox() {
        return new OutboxDefinition(TABLE, List.of(OutboundEventType.values()));
    }
}
