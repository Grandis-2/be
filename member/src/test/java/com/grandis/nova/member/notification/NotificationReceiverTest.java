package com.grandis.nova.member.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.member.support.Concurrently;
import com.grandis.nova.member.support.MemberIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 알림 요청 한 건 → 받는 회원마다 notifications 한 행. 큐 없이 처리 함수를 바로 부른다(실제 MySQL). 큐 배선은 NotificationConsumerTest 가 본다. */
@MemberIntegrationTest
@DisplayName("알림 저장 — 받는 회원마다 한 행, 같은 (eventId, 회원)은 한 행, 계약 위반은 거절 (실제 MySQL)")
class NotificationReceiverTest {

    @Autowired NotificationReceiver receiver;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meterRegistry;

    /** 시험 메시지의 kind 는 매번 새 값이라 지표에는 OTHER 로 잡힌다. 지표는 앞뒤 차이로 본다. */
    static final String OTHER = NotificationReceiver.OTHER_KIND;

    @Test
    @DisplayName("받는 회원이 여럿이면 회원마다 한 행 — 문구 · 갈 곳 · 원인 · 발생 시각을 그대로 담고 안 읽음으로 시작한다")
    void storesOneRowPerRecipient() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        NotificationMessages message = new NotificationMessages(first, second, third);

        receiver.receive(message.body());

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM notifications WHERE event_id = ?", message.eventId);
        assertThat(rows).extracting(row -> UuidBinary.fromBytes((byte[]) row.get("customer_id")))
                .containsExactlyInAnyOrder(first, second, third);
        assertThat(rows).extracting(row -> UuidBinary.fromBytes((byte[]) row.get("id"))).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id.version()).isEqualTo(7));
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("kind")).isEqualTo(message.kind);
            assertThat(row.get("title")).isEqualTo("사전예약 등록 완료");
            assertThat(row.get("body")).isEqualTo("사전예약 등록이 완료됐어요. 24시간 안에 결제해 주세요.");
            assertThat(row.get("target_type")).isEqualTo("PREORDER");
            assertThat(row.get("target_id")).isEqualTo("3c0f6a52-6d1b-4f7e-9b2a-0e5d8c1f4a77");
            assertThat(row.get("aggregate_type")).isEqualTo("PREORDER");
            assertThat(UuidBinary.fromBytes((byte[]) row.get("aggregate_id")))
                    .isEqualTo(UUID.fromString("0199a3f2-8a10-7b21-9c32-4d5e6f708192"));
            assertThat(((LocalDateTime) row.get("occurred_at")).toInstant(ZoneOffset.UTC))
                    .isEqualTo(Instant.parse("2026-10-15T03:00:00.123456Z"));
            assertThat(row.get("read_at")).isNull();
            assertThat(row.get("created_at")).isNotNull();
        });
    }

    @Test
    @DisplayName("1,000명 메시지 하나를 한 번에 저장한다 — 저장 지표는 회원 수만큼, 지연은 메시지마다 한 번")
    void storesThousandRecipientsAtOnce() {
        UUID[] recipients = IntStream.range(0, NotificationRequested.MAX_RECIPIENTS).mapToObj(i -> UUID.randomUUID()).toArray(UUID[]::new);
        NotificationMessages message = new NotificationMessages(recipients);
        double stored = stored(OTHER);
        long lags = meterRegistry.timer(NotificationReceiver.LAG_METRIC).count();

        receiver.receive(message.body());

        assertThat(rows(message.eventId)).isEqualTo(NotificationRequested.MAX_RECIPIENTS);
        assertThat(stored(OTHER) - stored).isEqualTo(NotificationRequested.MAX_RECIPIENTS);
        assertThat(meterRegistry.timer(NotificationReceiver.LAG_METRIC).count()).isEqualTo(lags + 1);
    }

    @Test
    @DisplayName("길이는 코드포인트로 센다 — 이모지 100개 제목은 100자다")
    void countsCodePoints() {
        NotificationMessages message = new NotificationMessages(UUID.randomUUID())
                .with(m -> m.payload().put("title", "🔔".repeat(100)));

        receiver.receive(message.body());

        assertThat(rows(message.eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("모르는 칸은 무시한다 — 보내는 쪽이 칸을 더해도 먼저 배포된 member 가 깨지지 않는다")
    void ignoresUnknownFields() {
        NotificationMessages message = new NotificationMessages(UUID.randomUUID()).with(m -> {
            m.envelope.put("traceId", "abc");
            m.payload().put("imageUrl", "https://cdn.example/1.png");
            ((ObjectNode) m.payload().get("target")).put("label", "상세");
        });

        receiver.receive(message.body());

        assertThat(rows(message.eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("지표의 kind 태그는 아는 종류만 그대로, 모르는 종류는 OTHER — 보내는 쪽 버그로 시계열이 늘지 않게")
    void kindTagIsBounded() {
        assertThat(NotificationReceiver.kindTag("DRAW_WON")).isEqualTo("DRAW_WON");
        assertThat(NotificationReceiver.kindTag("PREORDER_REGISTERED")).isEqualTo("PREORDER_REGISTERED");
        assertThat(NotificationReceiver.kindTag("PREORDER_OPENED")).isEqualTo("PREORDER_OPENED");
        assertThat(NotificationReceiver.kindTag("PREORDER_CANCELED")).isEqualTo("PREORDER_CANCELED");
        assertThat(NotificationReceiver.kindTag("TEST_" + UUID.randomUUID())).isEqualTo(OTHER);
    }

    @Test
    @DisplayName("같은 메시지를 다시 받으면 새로 넣지 않고 회원 수만큼 중복으로 센다 — 지연도 다시 남기지 않는다")
    void redeliveryStoresNothingNew() {
        NotificationMessages message = new NotificationMessages(UUID.randomUUID(), UUID.randomUUID());
        receiver.receive(message.body());
        double stored = stored(OTHER);
        double duplicates = duplicates(OTHER);
        long lags = meterRegistry.timer(NotificationReceiver.LAG_METRIC).count();

        receiver.receive(message.body());

        assertThat(rows(message.eventId)).isEqualTo(2);
        assertThat(stored(OTHER) - stored).isZero();
        assertThat(duplicates(OTHER) - duplicates).isEqualTo(2);
        assertThat(meterRegistry.timer(NotificationReceiver.LAG_METRIC).count()).isEqualTo(lags);
    }

    @Test
    @DisplayName("일부 회원이 이미 있으면 없는 회원만 넣는다")
    void partialOverlapInsertsOnlyMissing() {
        UUID already = UUID.randomUUID();
        UUID fresh1 = UUID.randomUUID();
        UUID fresh2 = UUID.randomUUID();
        NotificationMessages message = new NotificationMessages(already, fresh1, fresh2);
        String full = message.body();
        receiver.receive(message.with(m -> m.payload().putArray("recipientIds").add(already.toString())).body());
        double stored = stored(OTHER);
        double duplicates = duplicates(OTHER);

        receiver.receive(full);

        assertThat(jdbc.queryForList("SELECT customer_id FROM notifications WHERE event_id = ?", byte[].class, message.eventId))
                .extracting(UuidBinary::fromBytes).containsExactlyInAnyOrder(already, fresh1, fresh2);
        assertThat(stored(OTHER) - stored).isEqualTo(2);
        assertThat(duplicates(OTHER) - duplicates).isEqualTo(1);
    }

    /**
     * 읽은 뒤 다른 소비자가 먼저 넣으면 진 쪽은 유니크 키에 걸려 던진다(일시 오류 — 큐가 다시 준다). 다시 받으면 모두 걸러 중복으로 끝난다.
     * 결과로는 회원마다 한 행이고, 저장 지표는 회원 수만큼만 오른다.
     */
    @Test
    @DisplayName("같은 메시지를 동시에 받아도 회원마다 한 행 — 진 쪽은 DB 오류로 던지고, 다시 받으면 중복으로 끝난다")
    void concurrentSameMessageStoresOnce() throws Exception {
        NotificationMessages message = new NotificationMessages(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String body = message.body();
        double stored = stored(OTHER);

        List<Concurrently.Outcome<Object>> outcomes = Concurrently.run(8, i -> () -> {
            receiver.receive(body);
            return null;
        });

        assertThat(outcomes).filteredOn(outcome -> outcome.error() != null)
                .allSatisfy(outcome -> assertThat(outcome.error()).isInstanceOf(DataAccessException.class));
        assertThat(rows(message.eventId)).isEqualTo(3);
        assertThat(stored(OTHER) - stored).isEqualTo(3);

        double duplicates = duplicates(OTHER);
        receiver.receive(body);
        assertThat(rows(message.eventId)).isEqualTo(3);
        assertThat(duplicates(OTHER) - duplicates).isEqualTo(3);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contractViolations")
    @DisplayName("계약 위반은 NotificationRejected 로 올리고 저장하지 않는다 — 다시 받지 않고 DLQ 로 바로 간다")
    void rejectsContractViolation(String why, Consumer<NotificationMessages> change) {
        NotificationMessages message = new NotificationMessages(UUID.randomUUID()).with(change);

        assertThatThrownBy(() -> receiver.receive(message.body())).isInstanceOf(NotificationRejected.class);

        assertThat(rows(message.eventId)).isZero();
    }

    static Stream<Arguments> contractViolations() {
        return Stream.of(
                violation("다른 이벤트 종류", m -> m.envelope.put("eventType", "PREORDER_REGISTERED")),
                violation("eventId 없음", m -> m.envelope.remove("eventId")),
                violation("eventId 36자 초과", m -> m.envelope.put("eventId", m.eventId + "-x")),
                violation("occurredAt 없음", m -> m.envelope.remove("occurredAt")),
                violation("payload 없음", m -> m.envelope.remove("payload")),
                violation("recipientIds 칸 없음", m -> m.payload().remove("recipientIds")),
                violation("recipientIds 가 null", m -> m.payload().putNull("recipientIds")),
                violation("recipientIds 가 비었음", m -> m.payload().putArray("recipientIds")),
                violation("recipientIds 1,001명", m -> {
                    ArrayNode ids = m.payload().putArray("recipientIds");
                    IntStream.rangeClosed(0, NotificationRequested.MAX_RECIPIENTS).forEach(i -> ids.add(UUID.randomUUID().toString()));
                }),
                violation("recipientIds 에 같은 회원 두 번", m -> {
                    String id = UUID.randomUUID().toString();
                    m.payload().putArray("recipientIds").add(id).add(id);
                }),
                violation("recipientIds 에 UUID 가 아닌 값", m -> m.payload().putArray("recipientIds").add("member-1")),
                violation("recipientIds 에 null", m -> m.payload().putArray("recipientIds").addNull()),
                violation("recipientIds 가 배열이 아님", m -> m.payload().put("recipientIds", UUID.randomUUID().toString())),
                violation("옛 모양(recipientId 하나)", m -> {
                    m.payload().remove("recipientIds");
                    m.payload().put("recipientId", UUID.randomUUID().toString());
                }),
                violation("빈 제목", m -> m.payload().put("title", "  ")),
                violation("제목 없음", m -> m.payload().remove("title")),
                violation("제목 101자", m -> m.payload().put("title", "가".repeat(101))),
                violation("빈 본문", m -> m.payload().put("body", "")),
                violation("본문 501자", m -> m.payload().put("body", "가".repeat(501))),
                violation("aggregateType 31자", m -> m.envelope.put("aggregateType", "A".repeat(31))),
                violation("빈 kind", m -> m.payload().put("kind", "")),
                violation("kind 51자", m -> m.payload().put("kind", "K".repeat(51))),
                violation("target 없음", m -> m.payload().remove("target")),
                violation("target.id 없음", m -> ((ObjectNode) m.payload().get("target")).remove("id")),
                violation("target.id 101자", m -> ((ObjectNode) m.payload().get("target")).put("id", "i".repeat(101))),
                violation("target.type 빈 값", m -> ((ObjectNode) m.payload().get("target")).put("type", " ")),
                violation("target.type 31자", m -> ((ObjectNode) m.payload().get("target")).put("type", "T".repeat(31))));
    }

    @Test
    @DisplayName("거절 이유는 DLQ 에서 고칠 쪽을 가를 수 있게 나뉜다")
    void rejectionReasons() {
        assertThat(reasonOf("not-json")).isEqualTo(NotificationRejected.Reason.UNREADABLE);
        assertThat(reasonOf("null")).isEqualTo(NotificationRejected.Reason.UNREADABLE);
        assertThat(reasonOf(new NotificationMessages(UUID.randomUUID())
                .with(m -> m.envelope.put("eventType", "PREORDER_REGISTERED")).body()))
                .isEqualTo(NotificationRejected.Reason.NOT_NOTIFICATION);
        assertThat(reasonOf(new NotificationMessages(UUID.randomUUID())
                .with(m -> m.envelope.remove("eventId")).body()))
                .isEqualTo(NotificationRejected.Reason.INVALID_ENVELOPE);
        assertThat(reasonOf(new NotificationMessages(UUID.randomUUID())
                .with(m -> m.envelope.put("aggregateId", "not-a-uuid")).body()))
                .isEqualTo(NotificationRejected.Reason.UNREADABLE);
        assertThat(reasonOf(new NotificationMessages(UUID.randomUUID())
                .with(m -> m.payload().put("title", "")).body()))
                .isEqualTo(NotificationRejected.Reason.INVALID_PAYLOAD);
        assertThat(reasonOf(new NotificationMessages(UUID.randomUUID())
                .with(m -> m.payload().putArray("recipientIds")).body()))
                .isEqualTo(NotificationRejected.Reason.INVALID_PAYLOAD);
    }

    private NotificationRejected.Reason reasonOf(String body) {
        try {
            receiver.receive(body);
        } catch (NotificationRejected e) {
            return e.reason();
        }
        throw new AssertionError("거절되지 않았다");
    }

    private static Arguments violation(String why, Consumer<NotificationMessages> change) {
        return Arguments.of(why, change);
    }

    private int rows(String eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_id = ?", Integer.class, eventId);
    }

    private double stored(String kind) {
        return count("stored", kind);
    }

    private double duplicates(String kind) {
        return count("duplicate", kind);
    }

    private double count(String outcome, String kind) {
        var counter = meterRegistry.find(NotificationReceiver.METRIC).tags("outcome", outcome, "kind", kind).counter();
        return counter == null ? 0 : counter.count();
    }
}
