package com.grandis.nova.catalog.event;

import com.grandis.nova.common.message.EventEnvelope;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 받은 메시지를 이벤트 종류별 처리기({@link CatalogEventHandler})로 보낸다. 큐 소비기(common:sqs)는 본문을 그대로 여기에 넘긴다.
 *
 * 모르는 종류 · 깨진 본문 · 다른 aggregate 는 예외로 올린다. 건너뛰지 않는다 — 조용히 버리면 계약이 어긋난 것을 모른 채 메시지를 잃는다.
 * 소비기는 메시지를 지우지 않고 늦춰 다시 받다가, 재수신 한도를 넘으면 큐의 재드라이브 정책이 DLQ 로 옮긴다.
 *
 * 받은 이벤트에는 관리자 인증이 없다. catalog-events 에 쓸 수 있는 주체를 믿는다 — 큐 쓰기 권한이 신뢰 경계다.
 */
@Component
public class CatalogEventDispatcher {

    private final Map<String, CatalogEventHandler> handlers;
    private final JsonMapper jsonMapper;

    public CatalogEventDispatcher(List<CatalogEventHandler> handlers, JsonMapper jsonMapper) {
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(CatalogEventHandler::eventType, Function.identity(),
                (first, second) -> {
                    throw new IllegalStateException("같은 이벤트 종류를 처리기 둘이 맡는다: " + first.eventType());
                }));
        this.jsonMapper = jsonMapper;
    }

    /**
     * @throws IllegalArgumentException 봉투가 아니거나 eventType · payload 가 없음 · 받지 않는 이벤트 종류 · aggregate 가 다르거나 id 가 없음
     * @throws tools.jackson.core.JacksonException 본문이 JSON 이 아님
     */
    public void dispatch(String body) {
        EventEnvelope envelope = jsonMapper.readValue(body, EventEnvelope.class);
        if (envelope == null || envelope.eventType() == null || envelope.payload() == null) {
            throw new IllegalArgumentException("봉투가 아니거나 eventType · payload 가 없다: eventId=%s"
                    .formatted(envelope == null ? null : envelope.eventId()));
        }
        CatalogEventHandler handler = handlers.get(envelope.eventType());
        if (handler == null) {
            throw new IllegalArgumentException("받지 않는 이벤트 종류: eventId=%s, eventType=%s"
                    .formatted(envelope.eventId(), envelope.eventType()));
        }
        if (!handler.aggregateType().equals(envelope.aggregateType()) || envelope.aggregateId() == null) {
            throw new IllegalArgumentException("%s 이벤트가 아니다: eventId=%s, eventType=%s, aggregateType=%s, aggregateId=%s"
                    .formatted(handler.aggregateType(), envelope.eventId(), envelope.eventType(), envelope.aggregateType(),
                            envelope.aggregateId()));
        }
        handler.handle(envelope.aggregateId(), envelope);
    }
}
