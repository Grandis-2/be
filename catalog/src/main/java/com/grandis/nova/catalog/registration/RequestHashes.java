package com.grandis.nova.catalog.registration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 원본 요청의 정규화 해시. 바이트 해시는 키 순서 · 공백 · 숫자 표기에 갈리므로, 파싱한 DTO 를 정해진 규칙으로 다시 직렬화한 것을 SHA-256 한다 —
 * 객체 키는 사전순, 공백 없음, 숫자는 소수점 이하 0 을 뗀 평문(1000 과 1000.0 이 같다), **null 칸은 뺀다**(DTO 에 선택 칸이 늘어도
 * 진행 중 등록의 해시가 안 바뀐다). 배열 순서는 본문의 일부다 — combinations 순서만 바꿔도 다른 요청이다.
 * 같은 키에 해시가 다르면 다른 요청이다.
 */
public final class RequestHashes {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private RequestHashes() {
    }

    public static byte[] sha256(Object request) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical(request).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 시험 · 진단용. 정규화된 문자열 그대로. */
    static String canonical(Object request) {
        StringBuilder out = new StringBuilder();
        write(MAPPER.valueToTree(request), out);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(entry -> {
                if (!entry.getValue().isNull()) {
                    sorted.put(entry.getKey(), entry.getValue());
                }
            });
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(MAPPER.writeValueAsString(entry.getKey())).append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(items::add);
            out.append('[');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(items.get(i), out);
            }
            out.append(']');
        } else if (node.isNumber()) {
            BigDecimal number = node.decimalValue().stripTrailingZeros();
            out.append(number.toPlainString());
        } else if (node.isString()) {
            out.append(MAPPER.writeValueAsString(node.asString()));
        } else {
            out.append(node.isNull() ? "null" : node.asString());
        }
    }
}
