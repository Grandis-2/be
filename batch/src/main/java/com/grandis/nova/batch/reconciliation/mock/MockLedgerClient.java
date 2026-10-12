package com.grandis.nova.batch.reconciliation.mock;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mock 원장 목록 조회(GET /external/reservations). 외부 키 순서로 한 페이지씩 받아 nextCursor 가 null 일 때까지 흘려 준다.
 * 다음 페이지는 앞 페이지를 다 넘긴 뒤에 부르므로 원장 전체를 메모리에 올리지 않는다.
 *
 * 다시 보내지 않는다 — 한 페이지라도 못 받으면 그 실행을 판정 불가로 끝낸다({@link MockLedgerUnavailable}).
 * 응답이 계약(Mock docs/api.md)과 다르거나 키 순서가 어긋나도 같다. 순서가 어긋나면 be 예약과 맞대기가 틀어진다.
 */
public class MockLedgerClient {

    static final String PATH = "/external/reservations";
    private static final Set<String> STATUSES = Set.of(MockLedgerEntry.ACTIVE, MockLedgerEntry.CANCELED);

    private final RestClient rest;
    private final JsonMapper json;
    private final int pageSize;
    private final int maxBody;

    /**
     * @param rest    Mock 주소와 제한 시간이 걸린 클라이언트
     * @param maxBody 한 페이지 본문을 읽는 상한(바이트)
     */
    public MockLedgerClient(RestClient rest, JsonMapper json, int pageSize, int maxBody) {
        this.rest = rest;
        this.json = json;
        this.pageSize = pageSize;
        this.maxBody = maxBody;
    }

    /** 원장 전체를 외부 키 순서로. 페이지를 받다 실패하면 next() · hasNext() 가 {@link MockLedgerUnavailable} 을 던진다 */
    public Iterator<MockLedgerEntry> entries() {
        return new Pages();
    }

    Page fetch(int page, String cursor) {
        try {
            return rest.get()
                    .uri(builder -> {
                        builder.path(PATH).queryParam("size", pageSize);
                        if (cursor != null) {
                            builder.queryParam("cursor", cursor);
                        }
                        return builder.build();
                    })
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        byte[] body = response.getBody().readNBytes(maxBody + 1);
                        if (status != 200) {
                            throw new MockLedgerUnavailable(page, "HTTP " + status);
                        }
                        if (body.length > maxBody) {
                            throw new MockLedgerUnavailable(page, "응답 본문이 상한(" + maxBody + "바이트)을 넘음");
                        }
                        return parse(page, body);
                    });
        } catch (RestClientException e) {
            throw new MockLedgerUnavailable(page, "응답을 받지 못함 — " + rootCause(e), e);
        }
    }

    private Page parse(int page, byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            throw new MockLedgerUnavailable(page, "응답이 JSON 이 아님", e);
        }
        if (root == null || !root.isObject()) {
            throw contractBroken(page, "본문이 객체가 아님");
        }
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) {
            throw contractBroken(page, "items 가 배열이 아님");
        }
        List<MockLedgerEntry> entries = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            entries.add(entry(page, item));
        }
        return new Page(entries, nullableText(page, root, "nextCursor"));
    }

    private MockLedgerEntry entry(int page, JsonNode item) {
        if (item == null || !item.isObject()) {
            throw contractBroken(page, "items 의 한 건이 객체가 아님");
        }
        String key = requiredText(page, item, "externalKey");
        String status = requiredText(page, item, "status");
        if (!STATUSES.contains(status)) {
            throw contractBroken(page, "모르는 status " + status + " (키 " + key + ")");
        }
        return new MockLedgerEntry(key, nullableText(page, item, "externalNumber"), nullableText(page, item, "customerId"),
                nullableText(page, item, "productId"), nullableText(page, item, "sku"), status,
                instant(page, item, "confirmedAt"), instant(page, item, "canceledAt"));
    }

    private static String requiredText(int page, JsonNode node, String field) {
        String value = nullableText(page, node, field);
        if (value == null || value.isBlank()) {
            throw contractBroken(page, field + " 가 비어 있음");
        }
        return value;
    }

    /** 값이 없어도 필드는 생략하지 않는다(계약). 빠졌으면 계약 위반이다 */
    private static String nullableText(int page, JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw contractBroken(page, field + " 필드가 없음");
        }
        if (value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw contractBroken(page, field + " 가 문자열이 아님");
        }
        return value.stringValue();
    }

    private static Instant instant(int page, JsonNode node, String field) {
        String value = nullableText(page, node, field);
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw contractBroken(page, field + " 가 시각이 아님: " + value);
        }
    }

    private static MockLedgerUnavailable contractBroken(int page, String detail) {
        return new MockLedgerUnavailable(page, "응답이 계약과 다름 — " + detail);
    }

    private static String rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    record Page(List<MockLedgerEntry> entries, String nextCursor) {
    }

    /** 페이지를 필요할 때 하나씩 받는다. 키가 앞 행보다 커야 한다(외부 키 순서) */
    private final class Pages implements Iterator<MockLedgerEntry> {

        private int page;
        private Iterator<MockLedgerEntry> current;
        private String nextCursor;
        private boolean last;
        private String previousKey;

        @Override
        public boolean hasNext() {
            while ((current == null || !current.hasNext()) && !last) {
                Page fetched = fetch(++page, nextCursor);
                current = fetched.entries().iterator();
                nextCursor = fetched.nextCursor();
                last = nextCursor == null;
                if (!last && fetched.entries().isEmpty()) {
                    throw contractBroken(page, "빈 페이지인데 nextCursor 가 있음");
                }
            }
            return current.hasNext();
        }

        @Override
        public MockLedgerEntry next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            MockLedgerEntry entry = current.next();
            if (previousKey != null && entry.externalKey().compareTo(previousKey) <= 0) {
                throw contractBroken(page, "외부 키 순서가 아님(" + previousKey + " 다음 " + entry.externalKey() + ")");
            }
            previousKey = entry.externalKey();
            return entry;
        }
    }
}
