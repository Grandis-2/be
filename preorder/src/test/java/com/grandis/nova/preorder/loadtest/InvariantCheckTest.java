package com.grandis.nova.preorder.loadtest;

import com.grandis.nova.preorder.accept.PreorderAcceptService;
import com.grandis.nova.preorder.catalog.CatalogClient;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.Concurrently;
import com.grandis.nova.preorder.support.Concurrently.Outcome;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 부하 시험 뒤에 돌리는 불변식 조회(load-test/invariant-check.sql)가 동시 접수 결과를 맞게 세는지. */
@PreorderIntegrationTest
class InvariantCheckTest {

    static final int REQUESTS = 30;
    static final Path INVARIANT_SQL = Path.of("load-test/invariant-check.sql");

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MeterRegistry registry;

    @MockitoBean
    CatalogClient catalogClient;

    @Test
    void 동시_접수_뒤_유실_중복_불일치가_없다() throws Exception {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        AcceptFixtures accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        PreorderProduct product = fixtures.openPreorderProduct();
        accepts.stubCatalog(product);
        List<Long> customers = IntStream.range(0, REQUESTS).mapToObj(i -> fixtures.customer()).toList();
        long lockWaits = registry.get("preorder.campaign.lock.wait").timer().count();

        List<Outcome<Object>> outcomes =
                Concurrently.run(REQUESTS, i -> () -> accepts.submit(customers.get(i), product));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(registry.get("preorder.campaign.lock.wait").timer().count()).isEqualTo(lockWaits + REQUESTS);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> ((Number) invariants(product.productId()).get("unpublished_events")).longValue() == 0);
        Map<String, Object> invariants = invariants(product.productId());
        assertThat(invariants).containsEntry("total_accepted", (long) REQUESTS);
        for (String column : List.of("duplicate_positions", "position_gaps", "issued_position_mismatch",
                "duplicate_tickets", "register_job_mismatch", "register_event_mismatch")) {
            assertThat(((Number) invariants.get(column)).longValue()).as(column).isZero();
        }
    }

    @Test
    void 한_예약의_이벤트_누락과_다른_예약의_중복이_상쇄되지_않는다() {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        AcceptFixtures accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        PreorderProduct product = fixtures.openPreorderProduct();
        Long missing = accepts.accept(fixtures.customer(), product).preorder().getId();
        Long duplicated = accepts.accept(fixtures.customer(), product).preorder().getId();
        accepts.accept(fixtures.customer(), product);

        jdbcTemplate.update("""
                DELETE o FROM outbox_events o JOIN preorder_sync_jobs j ON j.id = o.aggregate_id
                 WHERE j.preorder_id = ? AND o.event_type = 'REGISTER_JOB_READY'
                """, missing);
        jdbcTemplate.update("""
                INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, created_at)
                SELECT UUID(), o.aggregate_type, o.aggregate_id, o.event_type, o.payload, o.created_at
                  FROM outbox_events o JOIN preorder_sync_jobs j ON j.id = o.aggregate_id
                 WHERE j.preorder_id = ? AND o.event_type = 'REGISTER_JOB_READY'
                """, duplicated);

        assertThat(((Number) invariants(product.productId()).get("register_event_mismatch")).longValue())
                .isEqualTo(2);
    }

    /** 세션 변수를 쓰므로 SET 과 SELECT 를 같은 커넥션에서 실행한다. */
    private Map<String, Object> invariants(Long productId) {
        return jdbcTemplate.execute((ConnectionCallback<Map<String, Object>>) connection -> {
            try (PreparedStatement set = connection.prepareStatement("SET @product_id = ?")) {
                set.setLong(1, productId);
                set.execute();
            }
            try (PreparedStatement select = connection.prepareStatement(Files.readString(INVARIANT_SQL));
                 ResultSet rs = select.executeQuery()) {
                rs.next();
                ResultSetMetaData meta = rs.getMetaData();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                return row;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
