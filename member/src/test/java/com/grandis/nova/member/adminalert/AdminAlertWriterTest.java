package com.grandis.nova.member.adminalert;

import com.grandis.nova.member.adminalert.AdminAlertWriter.Outcome;
import com.grandis.nova.member.support.Concurrently;
import com.grandis.nova.member.support.MemberIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 묶음 · 재알림 · 해소 · 순서 뒤바뀜을 실제 MySQL 에서 본다. 시계를 움직여야 해서 Writer 를 직접 만든다(트랜잭션 프록시 없이 문장마다 커밋된다).
 * 동시성은 TransactionTemplate 으로 트랜잭션을 씌워 본다. 표는 시험마다 비운다 — 이 표를 쓰는 시험은 이 패키지뿐이다.
 */
@MemberIntegrationTest
@DisplayName("관리자 알림 저장 — 묶음 · 재알림 · 해소 · 순서 (실제 MySQL)")
class AdminAlertWriterTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant T0 = Instant.parse("2026-10-26T05:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    final MutableClock clock = new MutableClock(T0);
    final TargetNames names = mock(TargetNames.class);
    AdminAlertIntake intake;
    GrafanaAlertReceiver grafana;
    AdminAlertEventReceiver events;
    TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM admin_alerts");
        when(names.find(any(), anyString())).thenReturn(TargetNames.Lookup.ABSENT);
        intake = new AdminAlertIntake(new AdminAlertWriter(jdbc, JSON, clock), names, new SimpleMeterRegistry());
        grafana = new GrafanaAlertReceiver(intake, JSON, clock);
        events = new AdminAlertEventReceiver(intake, JSON);
    }

    @Test
    @DisplayName("같은 사건은 한 행으로 묶어 숫자만 갱신하고, 2배 · 100 단위 · 5분 뒤 증가에만 다시 올리며 그때 안 읽음으로 돌린다")
    void groupsAndReraises() {
        String startsAt = "2026-10-26T05:00:00Z";
        grafana.receive(registerFailed(startsAt, "10"));
        markAllRead();

        clock.advance(Duration.ofMinutes(1));
        grafana.receive(registerFailed(startsAt, "15"));
        Map<String, Object> row = only("REGISTER_FAILED");
        assertThat(row.get("raised_at")).isEqualTo(utc(T0));
        assertThat(row.get("read_at")).isNotNull();
        assertThat(metric(row, "deadLetters")).isEqualTo("15");

        clock.advance(Duration.ofMinutes(1));
        grafana.receive(registerFailed(startsAt, "20"));
        row = only("REGISTER_FAILED");
        assertThat(row.get("raised_at")).isEqualTo(utc(clock.instant()));
        assertThat(row.get("read_at")).isNull();
        assertThat(((java.math.BigDecimal) row.get("raised_value"))).isEqualByComparingTo("20");

        markAllRead();
        clock.advance(Duration.ofMinutes(4));
        grafana.receive(registerFailed(startsAt, "21"));
        assertThat(only("REGISTER_FAILED").get("read_at")).as("4분 — 늘었지만 아직").isNotNull();
        clock.advance(Duration.ofMinutes(1));
        grafana.receive(registerFailed(startsAt, "20"));
        assertThat(only("REGISTER_FAILED").get("read_at")).as("5분이 지났지만 올린 값(20) 그대로").isNotNull();
        grafana.receive(registerFailed(startsAt, "22"));
        assertThat(only("REGISTER_FAILED").get("read_at")).as("5분이 지났고 늘었다").isNull();
    }

    @Test
    @DisplayName("resolved 가 오면 해소하고 해소 알림으로 올린다. 그 뒤에 늦게 온 firing 은 다시 열지 않는다")
    void resolvesAndIgnoresLateFiring() {
        String startsAt = "2026-10-26T05:00:00Z";
        grafana.receive(registerFailed(startsAt, "3"));
        markAllRead();
        clock.advance(Duration.ofMinutes(3));
        grafana.receive(AdminAlertMessages.Grafana.resolved("REGISTER_FAILED", startsAt, "2026-10-26T05:02:30Z")
                .metric("deadLetters", "0").body());

        Map<String, Object> row = only("REGISTER_FAILED");
        assertThat(row.get("status")).isEqualTo("RESOLVED");
        assertThat(row.get("resolved_at")).isEqualTo(utc(Instant.parse("2026-10-26T05:02:30Z")));
        assertThat(row.get("read_at")).isNull();
        assertThat(metric(row, "deadLetters")).isEqualTo("0");

        grafana.receive(registerFailed(startsAt, "5"));
        assertThat(only("REGISTER_FAILED").get("status")).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("resolved 가 firing 보다 먼저 오면 해소된 행으로 바로 넣는다")
    void resolvedFirst() {
        grafana.receive(AdminAlertMessages.Grafana.resolved("EVENT_DLQ_WAITING", "2026-10-26T04:50:00Z", "2026-10-26T04:58:00Z").body());
        grafana.receive(AdminAlertMessages.Grafana.firing("EVENT_DLQ_WAITING", "2026-10-26T04:50:00Z").metric("waiting", "4").body());

        Map<String, Object> row = only("EVENT_DLQ_WAITING");
        assertThat(row.get("status")).isEqualTo("RESOLVED");
        assertThat(row.get("audience")).isEqualTo("DEVELOPER");
    }

    @Test
    @DisplayName("새 사건이 오면 같은 묶음의 열린 앞 사건을 닫고, 앞 사건의 늦은 메시지는 넣지 않는다")
    void supersedes() {
        grafana.receive(registerFailed("2026-10-26T04:00:00Z", "3"));
        grafana.receive(registerFailed("2026-10-26T05:00:00Z", "4"));

        List<Map<String, Object>> rows = rows("REGISTER_FAILED");
        assertThat(rows).extracting(r -> r.get("episode")).containsExactly("2026-10-26T04:00:00Z", "2026-10-26T05:00:00Z");
        assertThat(rows).extracting(r -> r.get("status")).containsExactly("RESOLVED", "OPEN");
        assertThat(rows.getFirst().get("resolved_at")).isEqualTo(utc(Instant.parse("2026-10-26T05:00:00Z")));

        grafana.receive(registerFailed("2026-10-26T03:00:00Z", "9"));
        assertThat(rows("REGISTER_FAILED")).hasSize(2);
    }

    @Test
    @DisplayName("대상이 다른 경보는 다른 묶음이고, 두 받는 사람의 type 은 사건 하나에 두 행이다")
    void targetsAndAudiences() {
        grafana.receive(AdminAlertMessages.Grafana.firing("SERVICE_INCIDENT", "2026-10-26T05:00:00Z").target("SERVICE", "order").body());
        grafana.receive(AdminAlertMessages.Grafana.firing("SERVICE_INCIDENT", "2026-10-26T05:01:00Z").target("SERVICE", "payment").body());

        List<Map<String, Object>> rows = rows("SERVICE_INCIDENT");
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(r -> r.get("target_id") + "/" + r.get("audience") + "/" + r.get("status"))
                .containsExactlyInAnyOrder("order/ADMIN/OPEN", "order/DEVELOPER/OPEN", "payment/ADMIN/OPEN", "payment/DEVELOPER/OPEN");
    }

    @Test
    @DisplayName("QUEUE_SERVER_UNHEALTHY 는 처음 생긴 뒤 5분이 지난 갱신에서 URGENT 로 올리고 다시 올린다(한 번만)")
    void escalates() {
        String startsAt = "2026-10-26T05:00:00Z";
        grafana.receive(AdminAlertMessages.Grafana.firing("QUEUE_SERVER_UNHEALTHY", startsAt).body());
        markAllRead();
        clock.advance(Duration.ofMinutes(4));
        grafana.receive(AdminAlertMessages.Grafana.firing("QUEUE_SERVER_UNHEALTHY", startsAt).body());
        assertThat(only("QUEUE_SERVER_UNHEALTHY").get("severity")).isEqualTo("CHECK");

        clock.advance(Duration.ofMinutes(1));
        grafana.receive(AdminAlertMessages.Grafana.firing("QUEUE_SERVER_UNHEALTHY", startsAt).body());
        Map<String, Object> row = only("QUEUE_SERVER_UNHEALTHY");
        assertThat(row.get("severity")).isEqualTo("URGENT");
        assertThat(row.get("read_at")).isNull();

        markAllRead();
        clock.advance(Duration.ofMinutes(10));
        grafana.receive(AdminAlertMessages.Grafana.firing("QUEUE_SERVER_UNHEALTHY", startsAt).body());
        assertThat(only("QUEUE_SERVER_UNHEALTHY").get("read_at")).isNotNull();
    }

    @Test
    @DisplayName("한 번 오는 소식은 넣을 때 RESOLVED(해소 시각 = 발생 시각)이고, 다시 받아도 한 행이다")
    void once() {
        UUID product = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        AdminAlertMessages.Event opened = AdminAlertMessages.Event.preorder("PREORDER_OPENED", product, campaign, "2026-10-26T04:00:00Z");
        events.receive(opened.body());
        events.receive(AdminAlertMessages.Event.preorder("PREORDER_OPENED", product, campaign, "2026-10-26T04:00:05Z").body());

        Map<String, Object> row = only("PREORDER_OPENED");
        assertThat(row.get("status")).isEqualTo("RESOLVED");
        assertThat(row.get("resolved_at")).isEqualTo(utc(Instant.parse("2026-10-26T04:00:00Z")));
        assertThat(row.get("severity")).isEqualTo("INFO");
        assertThat(row.get("last_event_id")).isEqualTo(opened.eventId);

        events.receive(AdminAlertMessages.Event.preorder("PREORDER_OPENED", product, UUID.randomUUID(), "2026-10-27T04:00:00Z").body());
        assertThat(rows("PREORDER_OPENED")).as("다른 회차는 다른 사건").hasSize(2);
    }

    @Test
    @DisplayName("DRAW_DELAYED 는 검사마다 와도 한 행이고, DRAW_SETTLED 가 해소한다. 그 뒤에 늦게 온 DRAW_DELAYED 는 넣지 않는다")
    void drawDelayedResolvedBySettled() {
        UUID draw = UUID.randomUUID();
        events.receive(AdminAlertMessages.Event.draw("DRAW_DELAYED", draw, "2026-10-26T05:00:00Z").body());
        events.receive(AdminAlertMessages.Event.draw("DRAW_DELAYED", draw, "2026-10-26T05:10:00Z").body());
        events.receive(AdminAlertMessages.Event.draw("DRAW_DELAYED", draw, "2026-10-26T05:10:00.5Z").body());
        assertThat(rows("DRAW_DELAYED")).hasSize(1);
        markAllRead();

        events.receive(AdminAlertMessages.Event.draw("DRAW_SETTLED", draw, "2026-10-26T05:15:00Z").body());
        Map<String, Object> delayed = only("DRAW_DELAYED");
        assertThat(delayed.get("status")).isEqualTo("RESOLVED");
        assertThat(delayed.get("resolved_at")).isEqualTo(utc(Instant.parse("2026-10-26T05:15:00Z")));
        assertThat(delayed.get("read_at")).isNull();
        assertThat(only("DRAW_SETTLED").get("status")).isEqualTo("RESOLVED");

        UUID other = UUID.randomUUID();
        events.receive(AdminAlertMessages.Event.draw("DRAW_SETTLED", other, "2026-10-26T05:15:00Z").body());
        events.receive(AdminAlertMessages.Event.draw("DRAW_DELAYED", other, "2026-10-26T05:10:00Z").body());
        assertThat(rows("DRAW_DELAYED")).hasSize(1);
    }

    @Test
    @DisplayName("새 상품 · 회차 사건만 이름을 묻고 받은 이름을 저장한다. 이미 있는 사건은 다시 묻지 않는다")
    void namesOnlyNewRows() {
        UUID draw = UUID.randomUUID();
        when(names.find(AdminAlertRules.TargetKind.DRAW, draw.toString())).thenReturn(new TargetNames.Lookup("매직 키보드", false));
        events.receive(AdminAlertMessages.Event.draw("DRAW_DELAYED", draw, "2026-10-26T05:00:00Z").body());
        assertThat(only("DRAW_DELAYED").get("target_name")).isEqualTo("매직 키보드");

        TargetNames quiet = mock(TargetNames.class);
        AdminAlertIntake again = new AdminAlertIntake(new AdminAlertWriter(jdbc, JSON, clock), quiet, new SimpleMeterRegistry());
        new AdminAlertEventReceiver(again, JSON).receive(AdminAlertMessages.Event.draw("DRAW_DELAYED", draw, "2026-10-26T05:10:00Z").body());
        verify(quiet, never()).find(any(), anyString());

        grafana.receive(registerFailed("2026-10-26T05:00:00Z", "1"));
        verify(names, never()).find(AdminAlertRules.TargetKind.NONE, "");
    }

    @Test
    @DisplayName("두 소비자가 같은 새 사건을 동시에 넣으면(둘 다 없음을 읽은 뒤) 한쪽만 넣고 진 쪽은 중복 키로 끝난다 — 다시 받으면 갱신으로 지난다")
    void concurrentInsert() throws Exception {
        AdminAlertSignal signal = grafana.parse(registerFailed("2026-10-26T05:00:00Z", "1")).getFirst();
        // 둘 다 잠금 읽기(없음)를 마친 뒤에 넣게 맞춘다 — 그래야 진 쪽이 반드시 생긴다
        CyclicBarrier bothRead = new CyclicBarrier(2);
        AdminAlertWriter racing = new AdminAlertWriter(new HookedJdbc(jdbc, sql -> sql.contains("FOR UPDATE"), () -> await(bothRead), null),
                JSON, clock);

        List<Concurrently.Outcome<Outcome>> results = Concurrently.run(2, i -> () -> transactions.execute(tx -> racing.apply(signal, null)));

        assertThat(rows("REGISTER_FAILED")).hasSize(1);
        assertThat(results).filteredOn(Concurrently.Outcome::succeeded).singleElement()
                .extracting(Concurrently.Outcome::value).isEqualTo(Outcome.CREATED);
        assertThat(results).filteredOn(r -> !r.succeeded()).singleElement()
                .satisfies(r -> assertThat(r.error()).isInstanceOf(DuplicateKeyException.class));
        AdminAlertWriter plain = new AdminAlertWriter(jdbc, JSON, clock);
        Outcome again = transactions.execute(tx -> plain.apply(signal, null));
        assertThat(again).isEqualTo(Outcome.UPDATED);
    }

    @Test
    @DisplayName("경합 1 — SETTLED 가 넣고 해소(0행)까지 마친 뒤 커밋 전에 DELAYED 가 넣으면, DELAYED 의 잠금 재확인이 그 커밋을 기다렸다가 해소한다")
    void delayedAfterUncommittedSettledIsResolved() throws Exception {
        UUID draw = UUID.randomUUID();
        AdminAlertWriter writer = new AdminAlertWriter(jdbc, JSON, clock);
        AdminAlertSignal settled = events.parse(AdminAlertMessages.Event.draw("DRAW_SETTLED", draw, "2026-10-26T05:15:00Z").body());
        AdminAlertSignal delayed = events.parse(AdminAlertMessages.Event.draw("DRAW_DELAYED", draw, "2026-10-26T05:00:00Z").body());
        CountDownLatch settledApplied = new CountDownLatch(1);
        CountDownLatch commitSettled = new CountDownLatch(1);

        Thread settler = Thread.ofPlatform().start(() -> transactions.executeWithoutResult(tx -> {
            writer.apply(settled, null);
            settledApplied.countDown();
            awaitLatch(commitSettled);
        }));
        awaitLatch(settledApplied);
        AtomicReference<Outcome> delayedOutcome = new AtomicReference<>();
        Thread delayer = Thread.ofPlatform().start(() -> delayedOutcome.set(transactions.execute(tx -> writer.apply(delayed, null))));
        Thread.sleep(500);   // DELAYED 가 넣고 잠금 재확인에서 SETTLED 의 커밋을 기다리는 동안
        commitSettled.countDown();
        settler.join(10_000);
        delayer.join(10_000);

        assertThat(delayedOutcome.get()).isEqualTo(Outcome.RESOLVED);
        Map<String, Object> row = only("DRAW_DELAYED");
        assertThat(row.get("status")).isEqualTo("RESOLVED");
        assertThat(row.get("resolved_at")).isEqualTo(utc(Instant.parse("2026-10-26T05:15:00Z")));
    }

    @Test
    @DisplayName("경합 2 — DELAYED 가 넣고 재확인(없음)까지 마친 뒤 커밋 전에 SETTLED 가 오면, SETTLED 의 해소가 그 커밋을 기다렸다가 해소한다")
    void settledAfterUncommittedDelayedResolvesIt() throws Exception {
        UUID draw = UUID.randomUUID();
        AdminAlertWriter writer = new AdminAlertWriter(jdbc, JSON, clock);
        AdminAlertSignal settled = events.parse(AdminAlertMessages.Event.draw("DRAW_SETTLED", draw, "2026-10-26T05:15:00Z").body());
        AdminAlertSignal delayed = events.parse(AdminAlertMessages.Event.draw("DRAW_DELAYED", draw, "2026-10-26T05:00:00Z").body());
        CountDownLatch delayedApplied = new CountDownLatch(1);
        CountDownLatch commitDelayed = new CountDownLatch(1);
        AtomicReference<Outcome> delayedOutcome = new AtomicReference<>();

        Thread delayer = Thread.ofPlatform().start(() -> delayedOutcome.set(transactions.execute(tx -> {
            Outcome outcome = writer.apply(delayed, null);
            delayedApplied.countDown();
            awaitLatch(commitDelayed);
            return outcome;
        })));
        awaitLatch(delayedApplied);
        AtomicReference<Outcome> settledOutcome = new AtomicReference<>();
        Thread settler = Thread.ofPlatform().start(() -> settledOutcome.set(transactions.execute(tx -> writer.apply(settled, null))));
        Thread.sleep(500);   // SETTLED 의 해소(UPDATE)가 DELAYED 의 미커밋 행에서 기다리는 동안
        commitDelayed.countDown();
        delayer.join(10_000);
        settler.join(10_000);

        assertThat(delayedOutcome.get()).isEqualTo(Outcome.CREATED);
        assertThat(settledOutcome.get()).isEqualTo(Outcome.RESOLVED);
        assertThat(only("DRAW_DELAYED").get("status")).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("해소 메시지의 빈 값(null)은 마지막 firing 의 숫자를 지우지 않고, 값이 있으면 덮는다")
    void resolveKeepsLastValues() {
        String startsAt = "2026-10-26T05:00:00Z";
        grafana.receive(AdminAlertMessages.Grafana.firing("SHIPPING_BACKLOG", startsAt).metric("total", "58").metric("readyToShip", "13").body());
        grafana.receive(AdminAlertMessages.Grafana.resolved("SHIPPING_BACKLOG", startsAt, "2026-10-26T05:30:00Z")
                .metric("total", "NaN").metric("readyToShip", "4").body());

        Map<String, Object> row = only("SHIPPING_BACKLOG");
        assertThat(row.get("status")).isEqualTo("RESOLVED");
        assertThat(metric(row, "total")).isEqualTo("58");
        assertThat(metric(row, "readyToShip")).isEqualTo("4");
    }

    @Test
    @DisplayName("SQS 가 먼저 받은(SentTimestamp 가 이른) 메시지가 늦게 처리돼도 그사이 반영한 새 숫자를 덮지 않는다")
    void olderSentMessageDoesNotOverwrite() {
        String startsAt = "2026-10-26T05:00:00Z";
        grafana.receive(registerFailed(startsAt, "10"), T0.plusSeconds(120));
        grafana.receive(registerFailed(startsAt, "7"), T0.plusSeconds(60));

        assertThat(metric(only("REGISTER_FAILED"), "deadLetters")).isEqualTo("10");
    }

    private static String registerFailed(String startsAt, String deadLetters) {
        return AdminAlertMessages.Grafana.firing("REGISTER_FAILED", startsAt).metric("deadLetters", deadLetters).body();
    }

    private void markAllRead() {
        jdbc.update("UPDATE admin_alerts SET read_at = ? WHERE read_at IS NULL", utc(clock.instant()));
    }

    private Map<String, Object> only(String type) {
        List<Map<String, Object>> rows = rows(type);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private List<Map<String, Object>> rows(String type) {
        return jdbc.queryForList("SELECT * FROM admin_alerts WHERE type = ? ORDER BY first_occurred_at, audience", type);
    }

    private static String metric(Map<String, Object> row, String name) {
        return JSON.readTree((String) row.get("metrics")).get(name).asString();
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("기다림 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 정해진 문장의 앞 · 뒤에 끼어드는 JdbcTemplate — 같은 데이터소스라 트랜잭션도 같이 탄다. */
    static final class HookedJdbc extends JdbcTemplate {

        private final Predicate<String> match;
        private final Runnable after;
        private final Runnable afterScalar;

        HookedJdbc(JdbcTemplate real, Predicate<String> match, Runnable after, Runnable afterScalar) {
            super(real.getDataSource());
            this.match = match;
            this.after = after;
            this.afterScalar = afterScalar;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            List<T> result = super.query(sql, rowMapper, args);
            if (after != null && match.test(sql)) {
                after.run();
            }
            return result;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            T result = super.queryForObject(sql, requiredType, args);
            if (afterScalar != null && match.test(sql)) {
                afterScalar.run();
            }
            return result;
        }
    }

    /** 시험이 움직이는 시계. */
    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
