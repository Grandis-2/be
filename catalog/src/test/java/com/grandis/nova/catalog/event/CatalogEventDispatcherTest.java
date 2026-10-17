package com.grandis.nova.catalog.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandis.nova.common.message.EventEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("catalog-events 분배기 — 종류 이름으로 처리기를 찾고, 모르는 종류 · 다른 aggregate · id 없는 봉투는 실패로 올린다")
class CatalogEventDispatcherTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("종류가 맞는 처리기에 봉투의 aggregateId 와 payload 를 넘긴다 — 다른 처리기는 부르지 않는다")
    void routesByEventType() {
        Recording campaign = new Recording("CAMPAIGN_EVENT", "PREORDER_CAMPAIGN");
        Recording stock = new Recording("STOCK_EVENT", "PRODUCT");
        CatalogEventDispatcher dispatcher = new CatalogEventDispatcher(List.of(campaign, stock), JSON);
        UUID productId = UUID.randomUUID();

        dispatcher.dispatch(body("CAMPAIGN_EVENT", "PREORDER_CAMPAIGN", productId, "{\"n\":1}"));

        assertThat(campaign.ids).containsExactly(productId);
        assertThat(campaign.payloads).containsExactly("{\"n\":1}");
        assertThat(stock.ids).isEmpty();
    }

    @Test
    @DisplayName("처리기가 없는 종류는 실패로 올린다 — 조용히 지우지 않는다")
    void unknownTypeFails() {
        Recording campaign = new Recording("CAMPAIGN_EVENT", "PREORDER_CAMPAIGN");
        CatalogEventDispatcher dispatcher = new CatalogEventDispatcher(List.of(campaign), JSON);

        assertThatThrownBy(() -> dispatcher.dispatch(body("OTHER_EVENT", "PREORDER_CAMPAIGN", UUID.randomUUID(), "{}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OTHER_EVENT");
        assertThat(campaign.ids).isEmpty();
    }

    @Test
    @DisplayName("처리기가 하나도 없어도 뜨고, 받은 메시지는 모두 실패로 올린다")
    void noHandlersRejectsEverything() {
        CatalogEventDispatcher dispatcher = new CatalogEventDispatcher(List.of(), JSON);

        assertThatThrownBy(() -> dispatcher.dispatch(body("CAMPAIGN_EVENT", "PREORDER_CAMPAIGN", UUID.randomUUID(), "{}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("aggregateType 이 처리기와 다르면 처리기를 부르지 않고 실패로 올린다")
    void wrongAggregateFails() {
        Recording campaign = new Recording("CAMPAIGN_EVENT", "PREORDER_CAMPAIGN");
        CatalogEventDispatcher dispatcher = new CatalogEventDispatcher(List.of(campaign), JSON);

        assertThatThrownBy(() -> dispatcher.dispatch(body("CAMPAIGN_EVENT", "PRODUCT", UUID.randomUUID(), "{}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PREORDER_CAMPAIGN");
        assertThat(campaign.ids).isEmpty();
    }

    @Test
    @DisplayName("aggregateId 가 없으면 처리기를 부르지 않고 실패로 올린다")
    void missingAggregateIdFails() {
        Recording campaign = new Recording("CAMPAIGN_EVENT", "PREORDER_CAMPAIGN");
        CatalogEventDispatcher dispatcher = new CatalogEventDispatcher(List.of(campaign), JSON);

        assertThatThrownBy(() -> dispatcher.dispatch(body("CAMPAIGN_EVENT", "PREORDER_CAMPAIGN", null, "{}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(campaign.ids).isEmpty();
    }

    @Test
    @DisplayName("eventType 이 없거나 payload 가 빠졌거나 봉투가 JSON null 이면 eventId 를 담은 실패로 올리고 처리기를 부르지 않는다")
    void incompleteEnvelopeFails() {
        Recording campaign = new Recording("CAMPAIGN_EVENT", "PREORDER_CAMPAIGN");
        CatalogEventDispatcher dispatcher = new CatalogEventDispatcher(List.of(campaign), JSON);
        String id = UUID.randomUUID().toString();

        assertThatThrownBy(() -> dispatcher.dispatch("{\"eventId\":\"" + id + "\",\"aggregateType\":\"PREORDER_CAMPAIGN\",\"aggregateId\":\""
                + UUID.randomUUID() + "\",\"payload\":{}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(id);
        assertThatThrownBy(() -> dispatcher.dispatch("{\"eventId\":\"" + id + "\",\"eventType\":\"CAMPAIGN_EVENT\",\"aggregateType\":\"PREORDER_CAMPAIGN\",\"aggregateId\":\""
                + UUID.randomUUID() + "\"}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(id);
        assertThatThrownBy(() -> dispatcher.dispatch("null")).isInstanceOf(IllegalArgumentException.class);
        assertThat(campaign.ids).isEmpty();
    }

    @Test
    @DisplayName("payload 가 JSON null 이면 처리기에 NullNode 로 간다 — 모양 검사는 처리기 몫이다")
    void jsonNullPayloadReachesHandler() {
        Recording campaign = new Recording("CAMPAIGN_EVENT", "PREORDER_CAMPAIGN");
        CatalogEventDispatcher dispatcher = new CatalogEventDispatcher(List.of(campaign), JSON);

        dispatcher.dispatch("{\"eventId\":\"e\",\"eventType\":\"CAMPAIGN_EVENT\",\"aggregateType\":\"PREORDER_CAMPAIGN\",\"aggregateId\":\""
                + UUID.randomUUID() + "\",\"payload\":null}");

        assertThat(campaign.payloads).containsExactly("null");
    }

    @Test
    @DisplayName("깨진 본문은 실패로 올린다")
    void brokenBodyFails() {
        CatalogEventDispatcher dispatcher = new CatalogEventDispatcher(List.of(new Recording("CAMPAIGN_EVENT", "PREORDER_CAMPAIGN")), JSON);

        assertThatThrownBy(() -> dispatcher.dispatch("{not json")).isInstanceOf(JacksonException.class);
    }

    @Test
    @DisplayName("같은 종류를 처리기 둘이 맡으면 만들 때 실패한다")
    void duplicateTypeFailsAtStartup() {
        assertThatThrownBy(() -> new CatalogEventDispatcher(List.of(new Recording("CAMPAIGN_EVENT", "PREORDER_CAMPAIGN"),
                new Recording("CAMPAIGN_EVENT", "PRODUCT")), JSON))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CAMPAIGN_EVENT");
    }

    private static String body(String eventType, String aggregateType, UUID aggregateId, String payload) {
        return JSON.writeValueAsString(new EventEnvelope(UUID.randomUUID().toString(), eventType, aggregateType, aggregateId,
                Instant.parse("2026-10-17T00:00:00Z"), JSON.readTree(payload)));
    }

    private static final class Recording implements CatalogEventHandler {

        private final String eventType;
        private final String aggregateType;
        final List<UUID> ids = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();

        Recording(String eventType, String aggregateType) {
            this.eventType = eventType;
            this.aggregateType = aggregateType;
        }

        @Override
        public String eventType() {
            return eventType;
        }

        @Override
        public String aggregateType() {
            return aggregateType;
        }

        @Override
        public void handle(UUID aggregateId, EventEnvelope envelope) {
            ids.add(aggregateId);
            payloads.add(envelope.payload().toString());
        }
    }
}
