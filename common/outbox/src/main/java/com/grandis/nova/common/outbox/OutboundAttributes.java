package com.grandis.nova.common.outbox;

import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 메시지 본문 밖에 실을 추가 속성을 정한다. 서비스가 빈으로 등록하면 쓰고, 없으면 아무것도 싣지 않는다.
 * 릴레이가 행만 보고 다시 보내므로 값은 종류와 payload 에서만 끌어낸다 — 같은 행이면 늘 같은 값이어야 한다.
 */
@FunctionalInterface
public interface OutboundAttributes {

    OutboundAttributes NONE = (eventType, payload) -> Map.of();

    /** @return 실을 속성(없으면 빈 맵). eventType · eventId 는 전송 구현이 싣는 이름이라 쓸 수 없다 */
    Map<String, String> of(String eventType, JsonNode payload);
}
