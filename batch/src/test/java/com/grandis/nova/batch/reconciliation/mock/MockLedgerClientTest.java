package com.grandis.nova.batch.reconciliation.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandis.nova.batch.support.FakeMockLedger;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class MockLedgerClientTest {

    private static final FakeMockLedger MOCK = FakeMockLedger.start();
    private static final Instant AT = Instant.parse("2026-10-12T01:00:00.123Z");

    private final MockLedgerClient client = client(MOCK.url(), Duration.ofMinutes(1));

    @BeforeEach
    void reset() {
        MOCK.reset();
    }

    @AfterAll
    static void stop() {
        MOCK.close();
    }

    @Test
    void nextCursor_가_null_일_때까지_페이지를_따라_모든_행을_키_순서로_준다() {
        MOCK.active("k3", "R-3", "c3", "p", "SKU", AT);
        MOCK.active("k1", "R-1", "c1", "p", "SKU", AT);
        MOCK.cancelMarker("k2", AT);
        MOCK.active("k4", "R-4", "c4", "p", "SKU", AT);

        List<MockLedgerEntry> entries = drain(client.entries());

        assertThat(entries).extracting(MockLedgerEntry::externalKey).containsExactly("k1", "k2", "k3", "k4");
        assertThat(entries.get(1).cancelMarker()).isTrue();
        assertThat(entries.get(0).confirmedAt()).isEqualTo(AT);
        // 4건 · 페이지 2건 — 남은 행이 딱 페이지 크기면 nextCursor 가 null 이라 두 번만 부른다
        assertThat(MOCK.requests()).isEqualTo(2);
    }

    @Test
    void 원장이_비어_있으면_한_번만_부르고_끝난다() {
        assertThat(client.entries().hasNext()).isFalse();
        assertThat(MOCK.requests()).isEqualTo(1);
    }

    @Test
    void 중간_페이지가_계속_실패하면_그_페이지와_상태_시도_횟수를_담아_판정_불가로_던진다() {
        for (int i = 1; i <= 5; i++) {
            MOCK.active("k" + i, "R-" + i, "c", "p", "SKU", AT);
        }
        MOCK.failAtPage(2, 503);

        Iterator<MockLedgerEntry> entries = client.entries();

        assertThat(entries.next().externalKey()).isEqualTo("k1");
        assertThat(entries.next().externalKey()).isEqualTo("k2");
        assertThatThrownBy(entries::hasNext)
                .isInstanceOf(MockLedgerUnavailable.class)
                .hasMessage("Mock 목록 조회 실패(2페이지, HTTP 503 · 3번 시도)");
        // 1페이지 한 번 + 2페이지 처음 한 번과 다시 받기 두 번
        assertThat(MOCK.requests()).isEqualTo(4);
    }

    @Test
    void 일시_실패한_페이지는_다시_받아_이어_간다() {
        for (int i = 1; i <= 5; i++) {
            MOCK.active("k" + i, "R-" + i, "c", "p", "SKU", AT);
        }
        MOCK.failAtPage(2, 503, 1);

        List<MockLedgerEntry> entries = drain(client.entries());

        assertThat(entries).extracting(MockLedgerEntry::externalKey).containsExactly("k1", "k2", "k3", "k4", "k5");
        // 세 페이지 + 2페이지 다시 받기 한 번
        assertThat(MOCK.requests()).isEqualTo(4);
    }

    @Test
    void 주소_문제로_보이는_4xx_는_다시_받지_않는다() {
        MOCK.active("k1", "R-1", "c", "p", "SKU", AT);
        MOCK.failAtPage(1, 404);

        assertThatThrownBy(() -> drain(client.entries()))
                .isInstanceOf(MockLedgerUnavailable.class)
                .hasMessage("Mock 목록 조회 실패(1페이지, HTTP 404)");
        assertThat(MOCK.requests()).isEqualTo(1);
    }

    @Test
    void 기다리면_목록_전체_기한을_넘길_때는_다시_받지_않는다() {
        MOCK.active("k1", "R-1", "c", "p", "SKU", AT);
        MOCK.failAtPage(1, 503);
        MockLedgerClient patient = client(MOCK.url(), Duration.ofMinutes(1), Duration.ofHours(1));

        assertThatThrownBy(() -> drain(patient.entries()))
                .isInstanceOf(MockLedgerUnavailable.class)
                .hasMessage("Mock 목록 조회 실패(1페이지, HTTP 503)");
        assertThat(MOCK.requests()).isEqualTo(1);
    }

    @Test
    void 필드가_빠진_응답은_계약_위반으로_던진다() {
        MOCK.respondAtPage(1, """
                {"items":[{"externalKey":"k1","status":"ACTIVE"}],"nextCursor":null}
                """);

        assertThatThrownBy(() -> drain(client.entries()))
                .isInstanceOf(MockLedgerUnavailable.class)
                .hasMessageContaining("1페이지")
                .hasMessageContaining("externalNumber 필드가 없음");
    }

    @Test
    void 키가_순서대로_오지_않으면_맞대기가_틀어지므로_던진다() {
        MOCK.respondAtPage(1, """
                {"items":[%s,%s],"nextCursor":null}
                """.formatted(row("k2"), row("k1")));

        assertThatThrownBy(() -> drain(client.entries()))
                .isInstanceOf(MockLedgerUnavailable.class)
                .hasMessageContaining("외부 키 순서가 아님");
    }

    @Test
    void JSON_이_아닌_응답은_던진다() {
        MOCK.respondAtPage(1, "<html>gateway</html>");

        assertThatThrownBy(() -> drain(client.entries()))
                .isInstanceOf(MockLedgerUnavailable.class)
                .hasMessage("Mock 목록 조회 실패(1페이지, 응답이 JSON 이 아님)");
        // 계약과 다른 응답은 다시 받아도 같다
        assertThat(MOCK.requests()).isEqualTo(1);
    }

    @Test
    void 서버에_닿지_못하면_던진다() {
        MockLedgerClient nowhere = client("http://localhost:1", Duration.ofMinutes(1));

        // 연결 주소 같은 예외 원문은 화면에 나가지 않는다
        assertThatThrownBy(() -> nowhere.entries().hasNext())
                .isInstanceOf(MockLedgerUnavailable.class)
                .hasMessage("Mock 목록 조회 실패(1페이지, 응답을 받지 못함 — 연결 실패 · 3번 시도)");
    }

    @Test
    void 계약에_없는_값은_사유에_담지_않는다() {
        MOCK.respondAtPage(1, """
                {"items":[{"externalKey":"k1","externalNumber":null,"customerId":null,"productId":null,"sku":null,
                 "status":"SECRET-VALUE","confirmedAt":null,"canceledAt":null}],"nextCursor":null}
                """);

        assertThatThrownBy(() -> drain(client.entries()))
                .isInstanceOf(MockLedgerUnavailable.class)
                .hasMessage("Mock 목록 조회 실패(1페이지, 응답이 계약과 다름 — status 값이 계약에 없음)");
    }

    @Test
    void 목록_전체_기한을_넘기면_다음_페이지를_부르지_않고_던진다() {
        for (int i = 1; i <= 3; i++) {
            MOCK.active("k" + i, "R-" + i, "c", "p", "SKU", AT);
        }
        MockLedgerClient hurried = client(MOCK.url(), Duration.ofNanos(1));

        Iterator<MockLedgerEntry> entries = hurried.entries();
        entries.next();
        entries.next();

        assertThatThrownBy(entries::hasNext)
                .isInstanceOf(MockLedgerUnavailable.class)
                .hasMessage("Mock 목록 조회 실패(2페이지, 목록 전체 기한(0초)을 넘음)");
        assertThat(MOCK.requests()).isEqualTo(1);
    }

    /** 페이지 2건 · 다시 받기 2번(1ms, 2ms 뒤) */
    private static MockLedgerClient client(String url, Duration maxDuration) {
        return client(url, maxDuration, Duration.ofMillis(1));
    }

    /**
     * 운영(ReconciliationConfig)과 같은 JDK 클라이언트. 기본 빌더는 시험 경로에만 있는 Apache 클라이언트를 고르는데,
     * 그 클라이언트는 503 을 스스로 한 번 더 보내 요청 횟수가 달라진다
     */
    private static MockLedgerClient client(String url, Duration maxDuration, Duration retryDelay) {
        RestClient rest = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newHttpClient()))
                .baseUrl(url)
                .build();
        return new MockLedgerClient(rest, JsonMapper.builder().build(), 2, 1 << 20, maxDuration, 2, retryDelay);
    }

    private static String row(String key) {
        return """
                {"externalKey":"%s","externalNumber":"R-%s","customerId":"c","productId":"p","sku":"SKU",
                 "status":"ACTIVE","confirmedAt":"2026-10-12T01:00:00Z","canceledAt":null}
                """.formatted(key, key);
    }

    private static List<MockLedgerEntry> drain(Iterator<MockLedgerEntry> entries) {
        List<MockLedgerEntry> all = new ArrayList<>();
        entries.forEachRemaining(all::add);
        return all;
    }
}
