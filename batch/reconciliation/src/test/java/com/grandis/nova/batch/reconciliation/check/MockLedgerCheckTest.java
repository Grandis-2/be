package com.grandis.nova.batch.reconciliation.check;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.batch.reconciliation.mock.MockLedgerEntry;
import com.grandis.nova.batch.reconciliation.run.NewFinding;
import com.grandis.nova.batch.reconciliation.snapshot.BePreorder;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class MockLedgerCheckTest {

    private static final Instant AT = Instant.parse("2026-10-12T01:00:00Z");
    private static final String CUSTOMER = "0199a3f2-7c4e-7b20-9c3e-000000000001";
    private static final String PRODUCT = "0199a3f2-7c4e-7b20-9c3e-000000000002";
    /** 이 확인할 것은 경계 시각을 쓰지 않는다 */
    private static final RunScope SCOPE = new RunScope(null, AT, AT, 100, key -> false);

    private final MockLedgerCheck check = new MockLedgerCheck();

    @ParameterizedTest(name = "{0} · 등록 작업 {1} · Mock {2}")
    @CsvSource(nullValues = "NONE", value = {
            "REGISTERED,   SUCCEEDED,   ACTIVE",
            "RESERVED,     SUCCEEDED,   ACTIVE",
            "CANCELED,     SUCCEEDED,   CANCELED",
            "CANCELED,     CANCELED,    MARKER",
            "PENDING_SYNC, PENDING,     NONE",
            "PENDING_SYNC, PENDING,     ACTIVE",
            "PENDING_SYNC, DEAD_LETTER, NONE",
            "CANCELING,    SUCCEEDED,   ACTIVE",
            "CANCELING,    CANCELED,    NONE",
            "CANCELING,    CANCELED,    MARKER"})
    void 정상이거나_처리_중이면_남기지_않는다(String status, String registerJob, String mock) {
        assertThat(inspect(be(status, registerJob), mock(mock))).isEmpty();
    }

    @Test
    void Mock_에만_있는_취소_행은_남기지_않는다() {
        assertThat(inspect(null, mock("CANCELED"))).isEmpty();
        assertThat(inspect(null, mock("MARKER"))).isEmpty();
    }

    @ParameterizedTest(name = "{0} · 등록 작업 {1} · Mock {2} → {3}")
    @CsvSource(nullValues = "NONE", value = {
            "REGISTERED,   SUCCEEDED,   NONE,     REGISTERED_NOT_IN_MOCK",
            "RESERVED,     SUCCEEDED,   NONE,     REGISTERED_NOT_IN_MOCK",
            "REGISTERED,   SUCCEEDED,   CANCELED, REGISTERED_BUT_MOCK_CANCELED",
            "RESERVED,     SUCCEEDED,   MARKER,   REGISTERED_BUT_MOCK_CANCELED",
            "CANCELED,     SUCCEEDED,   ACTIVE,   CANCELED_BUT_MOCK_ACTIVE",
            "CANCELED,     CANCELED,    NONE,     CANCELED_WITHOUT_MOCK_MARKER",
            "PENDING_SYNC, PENDING,     MARKER,   PENDING_BUT_MOCK_CANCEL_MARKER",
            "PENDING_SYNC, DEAD_LETTER, MARKER,   PENDING_BUT_MOCK_CANCEL_MARKER",
            "PENDING_SYNC, DEAD_LETTER, ACTIVE,   PENDING_BUT_MOCK_ACTIVE"})
    void 상태와_Mock_행이_맞지_않으면_그_코드로_남긴다(String status, String registerJob, String mock, CheckCode code) {
        assertThat(inspect(be(status, registerJob), mock(mock))).extracting(NewFinding::checkCode)
                .containsExactly(code.name());
    }

    @Test
    void be_에_없는_키가_Mock_에_활성이면_Mock_에만_있음이다() {
        List<NewFinding> found = inspect(null, mock("ACTIVE"));

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.checkCode()).isEqualTo("MOCK_ONLY");
            assertThat(f.beSnapshot()).isNull();
            assertThat(f.mockSnapshot()).contains("\"externalKey\":\"k-1\"");
        });
    }

    @Test
    void 번호가_다르면_번호_다름이다() {
        MockLedgerEntry other = new MockLedgerEntry("k-1", "R-OTHER", CUSTOMER, PRODUCT, "SKU", "ACTIVE", AT, null);

        assertThat(inspect(be("REGISTERED", "SUCCEEDED"), other)).extracting(NewFinding::checkCode)
                .containsExactly("NUMBER_MISMATCH");
    }

    @Test
    void be_에_번호가_없으면_번호_다름이_아니다_번호_누락이_잡는다() {
        BePreorder noNumber = new BePreorder("k-1", "id", "REGISTERED", null, CUSTOMER, PRODUCT, "SKU", "SUCCEEDED",
                null, "REGISTERED", 1, 1, null, AT);

        assertThat(inspect(noNumber, mock("ACTIVE"))).isEmpty();
    }

    @ParameterizedTest(name = "{0} 이 다름")
    @CsvSource({"회원", "상품", "sku"})
    void 회원_상품_sku_가운데_하나라도_다르면_내용_다름이다(String field) {
        MockLedgerEntry other = new MockLedgerEntry("k-1", "R-1",
                field.equals("회원") ? "0199a3f2-7c4e-7b20-9c3e-0000000000ff" : CUSTOMER,
                field.equals("상품") ? "0199a3f2-7c4e-7b20-9c3e-0000000000ff" : PRODUCT,
                field.equals("sku") ? "OTHER-SKU" : "SKU", "ACTIVE", AT, null);

        assertThat(inspect(be("RESERVED", "SUCCEEDED"), other)).extracting(NewFinding::checkCode)
                .containsExactly("CONTENT_MISMATCH");
    }

    @Test
    void 번호와_내용이_둘_다_다르면_둘_다_남긴다() {
        MockLedgerEntry other = new MockLedgerEntry("k-1", "R-OTHER", CUSTOMER, PRODUCT, "OTHER-SKU", "ACTIVE", AT, null);

        assertThat(inspect(be("REGISTERED", "SUCCEEDED"), other)).extracting(NewFinding::checkCode)
                .containsExactly("NUMBER_MISMATCH", "CONTENT_MISMATCH");
    }

    private List<NewFinding> inspect(BePreorder be, MockLedgerEntry mock) {
        FindingSink sink = new FindingSink(JsonMapper.builder().build(), 100);
        check.inspect(new KeyPair("k-1", be, mock), SCOPE, sink);
        return sink.findings();
    }

    private static BePreorder be(String status, String registerJob) {
        String number = "SUCCEEDED".equals(registerJob) ? "R-1" : null;
        return new BePreorder("k-1", "id", status, number, CUSTOMER, PRODUCT, "SKU", registerJob, null, status, 1, 1,
                null, AT);
    }

    /** ACTIVE · CANCELED(등록 후 취소, 번호 있음) · MARKER(취소 표식) · null(행 없음) */
    private static MockLedgerEntry mock(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "ACTIVE" -> new MockLedgerEntry("k-1", "R-1", CUSTOMER, PRODUCT, "SKU", "ACTIVE", AT, null);
            case "CANCELED" -> new MockLedgerEntry("k-1", "R-1", CUSTOMER, PRODUCT, "SKU", "CANCELED", AT, AT);
            case "MARKER" -> new MockLedgerEntry("k-1", null, null, null, null, "CANCELED", null, AT);
            default -> throw new IllegalArgumentException(kind);
        };
    }
}
