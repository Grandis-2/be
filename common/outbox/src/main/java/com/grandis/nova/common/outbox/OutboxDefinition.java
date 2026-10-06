package com.grandis.nova.common.outbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 서비스가 소유한 아웃박스 표와 그 표에 적는 이벤트 종류. 서비스가 빈 하나로 준다 — 없으면 기동이 실패한다.
 *
 * 표 이름은 설정이 아니라 코드로 받는다. 설정 오타 · 환경 차이로 행이 다른 표에 갈라지지 않게 한다.
 * 표 이름을 SQL 에 이어 붙이므로 소문자 · 숫자 · 밑줄만 받는다. 릴레이는 행의 종류로 목적지를 찾고,
 * 여기 없는 종류는 기록하지 않는다.
 */
public final class OutboxDefinition {

    private static final Pattern TABLE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final int EVENT_TYPE_MAX_LENGTH = 50;

    private final String table;
    private final Map<String, String> destinations = new LinkedHashMap<>();

    /**
     * @param table      아웃박스 표 이름(스키마 없이)
     * @param eventTypes 이 표에 적는 이벤트 종류 전부
     */
    public OutboxDefinition(String table, List<? extends OutboxEventType> eventTypes) {
        if (table == null || !TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("아웃박스 표 이름은 소문자로 시작하는 소문자 · 숫자 · 밑줄 64자 이하여야 한다: " + table);
        }
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw new IllegalArgumentException("아웃박스 이벤트 종류가 없다: " + table);
        }
        this.table = table;
        for (OutboxEventType type : eventTypes) {
            String name = type.name();
            if (name == null || name.isBlank() || name.length() > EVENT_TYPE_MAX_LENGTH) {
                throw new IllegalArgumentException("이벤트 종류 이름은 1~" + EVENT_TYPE_MAX_LENGTH + "자여야 한다: " + name);
            }
            if (type.destination() == null || type.destination().isBlank()) {
                throw new IllegalArgumentException("이벤트 종류의 목적지가 없다: " + name);
            }
            if (destinations.putIfAbsent(name, type.destination()) != null) {
                throw new IllegalArgumentException("이벤트 종류 이름이 겹친다: " + name);
            }
        }
    }

    public static OutboxDefinition of(String table, OutboxEventType... eventTypes) {
        return new OutboxDefinition(table, List.of(eventTypes));
    }

    public String table() {
        return table;
    }

    /** @throws IllegalArgumentException 이 표에 등록되지 않은 종류 */
    String destinationOf(String eventType) {
        String destination = destinations.get(eventType);
        if (destination == null) {
            throw new IllegalArgumentException("아웃박스 " + table + " 에 등록되지 않은 이벤트 종류다: " + eventType);
        }
        return destination;
    }
}
