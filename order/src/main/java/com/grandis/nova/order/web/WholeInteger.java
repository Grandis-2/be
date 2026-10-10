package com.grandis.nova.order.web;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * JSON 정수 토큰만 받는 Integer 역직렬화. Jackson 기본값(ACCEPT_FLOAT_AS_INT)은 12.5 를 12 로 잘라 다른 수량이 조용히 들어가므로, 전역 설정을
 * 건드리지 않고 칸마다 `@JsonDeserialize(using = WholeInteger.class)` 로 소수를 거절한다(API 는 400, 이벤트는 재시도 뒤 DLQ).
 * 범위를 넘으면 파서가 거절한다.
 */
public final class WholeInteger extends ValueDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser p, DeserializationContext ctxt) {
        return p.currentToken() == JsonToken.VALUE_NUMBER_INT ? p.getIntValue()
                : (Integer) ctxt.handleUnexpectedToken(Integer.class, p);
    }
}
