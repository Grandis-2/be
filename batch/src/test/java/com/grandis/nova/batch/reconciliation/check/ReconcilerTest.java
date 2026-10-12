package com.grandis.nova.batch.reconciliation.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandis.nova.batch.reconciliation.mock.MockLedgerEntry;
import com.grandis.nova.batch.reconciliation.run.RunCounts;
import com.grandis.nova.batch.reconciliation.snapshot.BePreorder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReconcilerTest {

    private static final Instant CUTOFF = Instant.parse("2026-10-12T03:15:00Z");
    private static final Instant BEFORE = CUTOFF.minusSeconds(3600);
    private static final Instant AFTER = CUTOFF.plusSeconds(60);
    private static final String PRODUCT = "0199a3f2-7c4e-7b20-9c3e-4f5a6b7c8d9e";
    private static final String OTHER_PRODUCT = "0199a3f2-7c4e-7b20-9c3e-000000000000";

    private final List<KeyPair> seen = new ArrayList<>();
    private final Reconciler reconciler = new Reconciler(List.of((pair, findings) -> seen.add(pair)), List.of(),
            JsonMapper.builder().build());

    @Test
    void 두_쪽을_외부_키_순서로_맞대어_한쪽에만_있는_키도_한_번씩_넘긴다() {
        reconciler.reconcile(
                List.of(be("a", "REGISTERED", "SUCCEEDED", BEFORE), be("c", "REGISTERED", "SUCCEEDED", BEFORE)).iterator(),
                List.of(mock("b", PRODUCT, BEFORE), mock("c", PRODUCT, BEFORE), mock("d", PRODUCT, BEFORE)).iterator(),
                scope(null));

        assertThat(seen).extracting(KeyPair::key).containsExactly("a", "b", "c", "d");
        assertThat(seen.get(0).mock()).isNull();
        assertThat(seen.get(1).be()).isNull();
        assertThat(seen.get(2).be()).isNotNull();
        assertThat(seen.get(2).mock()).isNotNull();
    }

    @Test
    void 기준_시각_뒤에_바뀐_키는_판정하지_않고_판정_보류로_센다() {
        ReconcileResult result = reconciler.reconcile(
                List.of(be("a", "REGISTERED", "SUCCEEDED", AFTER), be("b", "REGISTERED", "SUCCEEDED", BEFORE)).iterator(),
                List.of(mock("b", PRODUCT, AFTER), mock("c", PRODUCT, BEFORE)).iterator(),
                scope(null));

        assertThat(seen).extracting(KeyPair::key).containsExactly("c");
        assertThat(result.counts().held()).isEqualTo(2);
    }

    @Test
    void 상품_하나만_검사하면_다른_상품의_Mock_행과_상품을_모르는_취소_표식은_버린다() {
        reconciler.reconcile(
                List.of(be("b", "REGISTERED", "SUCCEEDED", BEFORE)).iterator(),
                List.of(mock("a", OTHER_PRODUCT, BEFORE), mock("b", PRODUCT, BEFORE), marker("c", BEFORE),
                        mock("d", PRODUCT, BEFORE)).iterator(),
                scope(UUID.fromString(PRODUCT)));

        assertThat(seen).extracting(KeyPair::key).containsExactly("b", "d");
    }

    @Test
    void 처리_중_관리자_대기는_판정_보류와_상관없이_예약_상태로_센다() {
        ReconcileResult result = reconciler.reconcile(
                List.of(be("a", "PENDING_SYNC", "PENDING", AFTER), be("b", "PENDING_SYNC", "DEAD_LETTER", BEFORE),
                        be("c", "CANCELING", "SUCCEEDED", BEFORE), be("d", "RESERVED", "SUCCEEDED", BEFORE)).iterator(),
                List.<MockLedgerEntry>of().iterator(), scope(null));

        assertThat(result.counts()).isEqualTo(new RunCounts(1, 1, 1, 1));
    }

    @Test
    void 확인할_것이_남긴_결과는_같은_코드_대상이면_한_번만_남는다() {
        Reconciler twice = new Reconciler(List.of(
                (pair, findings) -> findings.add("MOCK_ONLY", pair.key(), null, pair.mock()),
                (pair, findings) -> findings.add("MOCK_ONLY", pair.key(), null, pair.mock())), List.of(),
                JsonMapper.builder().build());

        ReconcileResult result = twice.reconcile(List.<BePreorder>of().iterator(),
                List.of(mock("a", PRODUCT, BEFORE)).iterator(), scope(null));

        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.checkCode()).isEqualTo("MOCK_ONLY");
            assertThat(f.beSnapshot()).isNull();
            assertThat(f.mockSnapshot()).contains("\"externalKey\":\"a\"");
        });
    }

    @Test
    void 상품_하나만_검사할_때_이_상품의_Mock_행이라도_키가_다른_상품의_예약이면_뺀다() {
        reconciler.reconcile(List.<BePreorder>of().iterator(),
                List.of(mock("x", PRODUCT, BEFORE), mock("y", PRODUCT, BEFORE)).iterator(),
                new RunScope(UUID.fromString(PRODUCT), CUTOFF, 100, key -> key.equals("x")));

        assertThat(seen).extracting(KeyPair::key).containsExactly("y");
    }

    @Test
    void 불일치가_상한을_넘으면_더_모으지_않고_판정_불가로_던진다() {
        Reconciler flagging = new Reconciler(List.of((pair, findings) -> findings.add("MOCK_ONLY", pair.key(), null,
                pair.mock())), List.of(), JsonMapper.builder().build());

        assertThatThrownBy(() -> flagging.reconcile(List.<BePreorder>of().iterator(),
                List.of(mock("a", PRODUCT, BEFORE), mock("b", PRODUCT, BEFORE), mock("c", PRODUCT, BEFORE)).iterator(),
                new RunScope(null, CUTOFF, 2, key -> false)))
                .isInstanceOf(TooManyFindings.class)
                .hasMessage("불일치가 상한(2건)을 넘음 — Mock 주소 · 데이터 설정 확인 필요");
    }

    private static RunScope scope(UUID productId) {
        return new RunScope(productId, CUTOFF, 100, key -> false);
    }

    private static BePreorder be(String token, String status, String registerJob, Instant changedAt) {
        return new BePreorder(token, UUID.randomUUID().toString(), status, null, UUID.randomUUID().toString(), PRODUCT,
                "SKU", registerJob, null, status, 1, 1, null, changedAt);
    }

    private static MockLedgerEntry mock(String key, String productId, Instant confirmedAt) {
        return new MockLedgerEntry(key, "R-" + key, UUID.randomUUID().toString(), productId, "SKU", "ACTIVE",
                confirmedAt, null);
    }

    private static MockLedgerEntry marker(String key, Instant canceledAt) {
        return new MockLedgerEntry(key, null, null, null, null, "CANCELED", null, canceledAt);
    }
}
