package com.grandis.nova.batch.reconciliation.mock;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 응답을 받지 못했거나 5xx 면 잠깐 기다렸다 같은 페이지를 다시 받는다(읽기만 하는 GET 이고 커서가 같아 다시 받아도 안전하다).
 * 정해진 횟수를 다 써도 못 받으면 그 실행을 판정 불가로 끝낸다({@link MockLedgerUnavailable}). 4xx · 3xx · 본문 상한 초과 ·
 * 계약(Mock docs/api.md)과 다른 응답 · 키 순서 어긋남은 주소 · 계약 문제라 다시 받지 않고 바로 판정 불가다. 다시 받는 시간도
 * 목록 전체 기한 안에 든다 — 기다리면 기한을 넘기는 경우에는 다시 받지 않는다.
 * 실패 사유는 관리자 화면에 나가므로 고정 문구만 담는다. 예외 원문(연결 주소 등)과 응답 값은 로그에만 남긴다.
 */
public class MockLedgerClient {

    static final String PATH = "/external/reservations";
    private static final Logger log = LoggerFactory.getLogger(MockLedgerClient.class);
    private static final Set<String> STATUSES = Set.of(MockLedgerEntry.ACTIVE, MockLedgerEntry.CANCELED);

    private final RestClient rest;
    private final JsonMapper json;
    private final int pageSize;
    private final int maxBody;
    private final Duration maxDuration;
    private final int retries;
    private final Duration retryDelay;

    /**
     * @param rest        Mock 주소와 제한 시간이 걸린 클라이언트
     * @param maxBody     한 페이지 본문을 읽는 상한(바이트)
     * @param maxDuration 목록 전체를 받는 기한. 둘째 페이지부터 부르기 전과 다시 받기 전에 본다
     * @param retries     한 페이지를 일시 실패 뒤 다시 받는 횟수. 0 이면 다시 받지 않는다
     * @param retryDelay  처음 다시 받기 전에 기다리는 시간. 다음부터는 두 배씩
     */
    public MockLedgerClient(RestClient rest, JsonMapper json, int pageSize, int maxBody, Duration maxDuration,
                            int retries, Duration retryDelay) {
        this.rest = rest;
        this.json = json;
        this.pageSize = pageSize;
        this.maxBody = maxBody;
        this.maxDuration = maxDuration;
        this.retries = retries;
        this.retryDelay = retryDelay;
    }

    /** 원장 전체를 외부 키 순서로. 페이지를 받다 실패하면 next() · hasNext() 가 {@link MockLedgerUnavailable} 을 던진다 */
    public Iterator<MockLedgerEntry> entries() {
        return new Pages(System.nanoTime());
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
                        if (status >= 500 && status <= 599) {
                            throw MockLedgerUnavailable.retryable(page, "HTTP " + status, null);
                        }
                        if (status != 200) {
                            throw new MockLedgerUnavailable(page, "HTTP " + status);
                        }
                        if (body.length > maxBody) {
                            throw new MockLedgerUnavailable(page, "응답 본문이 상한(" + maxBody + "바이트)을 넘음");
                        }
                        return parse(page, body);
                    });
        } catch (RestClientException e) {
            log.warn("Mock 목록 조회 실패 {}페이지", page, e);
            throw MockLedgerUnavailable.retryable(page, "응답을 받지 못함 — " + reason(e), e);
        }
    }

    private Page parse(int page, byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            log.warn("Mock 목록 응답이 JSON 이 아님 {}페이지", page, e);
            throw new MockLedgerUnavailable(page, "응답이 JSON 이 아님", e);
        }
        if (root == null || !root.isObject()) {
            throw contractBroken(page, "본문이 객체가 아님", null);
        }
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) {
            throw contractBroken(page, "items 가 배열이 아님", null);
        }
        List<MockLedgerEntry> entries = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            entries.add(entry(page, item));
        }
        return new Page(entries, nullableText(page, root, "nextCursor"));
    }

    private MockLedgerEntry entry(int page, JsonNode item) {
        if (item == null || !item.isObject()) {
            throw contractBroken(page, "items 의 한 건이 객체가 아님", null);
        }
        String key = requiredText(page, item, "externalKey");
        String status = requiredText(page, item, "status");
        if (!STATUSES.contains(status)) {
            throw contractBroken(page, "status 값이 계약에 없음", "키 " + key + " status " + status);
        }
        return new MockLedgerEntry(key, nullableText(page, item, "externalNumber"), nullableText(page, item, "customerId"),
                nullableText(page, item, "productId"), nullableText(page, item, "sku"), status,
                instant(page, item, "confirmedAt"), instant(page, item, "canceledAt"));
    }

    private static String requiredText(int page, JsonNode node, String field) {
        String value = nullableText(page, node, field);
        if (value == null || value.isBlank()) {
            throw contractBroken(page, field + " 가 비어 있음", null);
        }
        return value;
    }

    /** 값이 없어도 필드는 생략하지 않는다(계약). 빠졌으면 계약 위반이다 */
    private static String nullableText(int page, JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw contractBroken(page, field + " 필드가 없음", null);
        }
        if (value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw contractBroken(page, field + " 가 문자열이 아님", null);
        }
        return value.stringValue();
    }

    private static Instant instant(int page, JsonNode node, String field) {
        String value = nullableText(page, node, field);
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw contractBroken(page, field + " 가 시각 형식이 아님", field + " " + value);
        }
    }

    /**
     * @param reason 화면에 나가는 고정 문구
     * @param detail 로그에만 남기는 응답 값. 없으면 null
     */
    private static MockLedgerUnavailable contractBroken(int page, String reason, String detail) {
        if (detail != null) {
            log.warn("Mock 목록 응답이 계약과 다름 {}페이지 — {} ({})", page, reason, detail);
        }
        return new MockLedgerUnavailable(page, "응답이 계약과 다름 — " + reason);
    }

    /** 화면에 나가는 통신 실패 사유. 예외 원문은 연결 주소를 담을 수 있어 로그에만 남긴다 */
    static String reason(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException) {
                return "시간 초과";
            }
            if (cause instanceof ConnectException) {
                return "연결 실패";
            }
        }
        return "통신 오류";
    }

    private static String readable(Duration duration) {
        return duration.toSecondsPart() == 0 && duration.toMinutes() > 0
                ? duration.toMinutes() + "분"
                : duration.toSeconds() + "초";
    }

    record Page(List<MockLedgerEntry> entries, String nextCursor) {
    }

    /** 페이지를 필요할 때 하나씩 받는다. 키가 앞 행보다 커야 한다(외부 키 순서) */
    private final class Pages implements Iterator<MockLedgerEntry> {

        private final long startedNanos;
        private int page;
        private Iterator<MockLedgerEntry> current;
        private String nextCursor;
        private boolean last;
        private String previousKey;

        Pages(long startedNanos) {
            this.startedNanos = startedNanos;
        }

        @Override
        public boolean hasNext() {
            while ((current == null || !current.hasNext()) && !last) {
                if (page > 0 && System.nanoTime() - startedNanos > maxDuration.toNanos()) {
                    throw new MockLedgerUnavailable(page + 1, "목록 전체 기한(" + readable(maxDuration) + ")을 넘음");
                }
                Page fetched = fetchRetrying(++page, nextCursor);
                current = fetched.entries().iterator();
                nextCursor = fetched.nextCursor();
                last = nextCursor == null;
                if (!last && fetched.entries().isEmpty()) {
                    throw contractBroken(page, "빈 페이지인데 nextCursor 가 있음", null);
                }
            }
            return current.hasNext();
        }

        /** 일시 실패면 기다렸다 같은 페이지를 다시 받는다. 횟수를 다 쓰거나 기다리면 기한을 넘기면 시도 횟수를 담아 던진다 */
        private Page fetchRetrying(int page, String cursor) {
            Duration delay = retryDelay;
            for (int attempt = 1; ; attempt++) {
                try {
                    return fetch(page, cursor);
                } catch (MockLedgerUnavailable e) {
                    if (!e.isRetryable()) {
                        throw e;
                    }
                    boolean pastDeadline = System.nanoTime() + delay.toNanos() - startedNanos > maxDuration.toNanos();
                    if (attempt > retries || pastDeadline) {
                        throw attempt == 1 ? e : e.afterAttempts(attempt);
                    }
                    log.warn("Mock 목록 {}페이지 다시 받음 — {}ms 뒤 {}번째 시도", page, delay.toMillis(), attempt + 1);
                    pause(delay, e);
                    delay = delay.multipliedBy(2);
                }
            }
        }

        private void pause(Duration delay, MockLedgerUnavailable failure) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure;
            }
        }

        @Override
        public MockLedgerEntry next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            MockLedgerEntry entry = current.next();
            if (previousKey != null && entry.externalKey().compareTo(previousKey) <= 0) {
                throw contractBroken(page, "외부 키 순서가 아님", previousKey + " 다음 " + entry.externalKey());
            }
            previousKey = entry.externalKey();
            return entry;
        }
    }
}
