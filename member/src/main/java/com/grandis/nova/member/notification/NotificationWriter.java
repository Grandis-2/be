package com.grandis.nova.member.notification;

import com.grandis.nova.common.UuidBinary;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 메시지 하나의 받는 회원들을 행으로 넣는다. NCP 왕복이 약 35ms 라 회원마다 INSERT 하면 1,000명에 35초다 — 이미 넣은 회원을 한 번 읽고
 * 나머지를 여러 행 INSERT 한 번으로 넣는다(수신자 수와 상관없이 SELECT · INSERT 한 번씩).
 *
 * 트랜잭션으로 묶지 않는다 — 쓰기는 INSERT 한 문장이라 그 자체로 전부 들어가거나 전부 빠지고, 읽기와 쓰기 사이의 경합은 유니크 키가 막는다.
 * 묶으면 시작 · 커밋 왕복만 는다. 읽은 뒤 다른 소비자가 같은 메시지를 먼저 넣었으면 uq_notification_event_customer 에 걸려
 * DuplicateKeyException 으로 끝난다. 그대로 던져 일시 오류로 다시 받게 한다 — 다시 받으면 읽기가 그 회원들을 거른다. INSERT IGNORE 는
 * 쓰지 않는다: 중복 말고 다른 오류(길이 · 형)까지 경고로 내려 조용히 잃는다.
 *
 * id 는 엔티티와 같은 v7 생성기에서 받되(Hibernate {@code @UuidGenerator(VERSION_7)} 가 쓰는 것), INSERT 의 DB 연결을 얻은 뒤 문장 직전에 만든다.
 * 실시간 연결({@link NotificationTail})은 id 순으로 새 행을 찾고 overlap(10초)만큼만 되돌아 읽는다 — id 를 먼저 만들면 연결 대기
 * (Hikari connectionTimeout, 최대 30초)가 id 시각과 커밋 사이에 끼어 그 행을 놓칠 수 있다.
 */
@Component
class NotificationWriter {

    private static final String INSERT = """
            INSERT INTO notifications (id, event_id, customer_id, kind, title, body, target_type, target_id,
                                       aggregate_type, aggregate_id, occurred_at, created_at)
            VALUES\s""";
    private static final String ROW = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final int COLUMNS = 12;

    private final JdbcTemplate jdbc;

    NotificationWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 받은 메시지 하나. 회원마다 같은 값이 들어간다. */
    record Incoming(String eventId, String aggregateType, UUID aggregateId, Instant occurredAt,
                    NotificationRequested request, Instant receivedAt) {
    }

    /**
     * @return 이번에 넣은 회원 수. 나머지는 이미 받은 회원이다
     * @throws org.springframework.dao.DuplicateKeyException 읽은 뒤 다른 소비자가 먼저 넣었다 — 다시 받으면 된다
     */
    int store(Incoming incoming) {
        Set<UUID> stored = new HashSet<>(jdbc.query("SELECT customer_id FROM notifications WHERE event_id = ?",
                (rs, i) -> UuidBinary.fromBytes(rs.getBytes(1)), incoming.eventId()));
        List<UUID> missing = incoming.request().recipientIds().stream().filter(id -> !stored.contains(id)).toList();
        if (missing.isEmpty()) {
            return 0;
        }
        String sql = INSERT + String.join(", ", Collections.nCopies(missing.size(), ROW));
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            new ArgumentPreparedStatementSetter(args(incoming, missing)).setValues(statement);
            return statement;
        });
        return missing.size();
    }

    private static Object[] args(Incoming incoming, List<UUID> recipients) {
        NotificationRequested request = incoming.request();
        LocalDateTime occurredAt = utc(incoming.occurredAt());
        LocalDateTime receivedAt = utc(incoming.receivedAt());
        byte[] aggregateId = UuidBinary.toBytes(incoming.aggregateId());
        Object[] args = new Object[recipients.size() * COLUMNS];
        int i = 0;
        for (UUID recipient : recipients) {
            args[i++] = UuidBinary.toBytes(UuidVersion7Strategy.INSTANCE.generateUuid(null));
            args[i++] = incoming.eventId();
            args[i++] = UuidBinary.toBytes(recipient);
            args[i++] = request.kind();
            args[i++] = request.title();
            args[i++] = request.body();
            args[i++] = request.target().type();
            args[i++] = request.target().id();
            args[i++] = incoming.aggregateType();
            args[i++] = aggregateId;
            args[i++] = occurredAt;
            args[i++] = receivedAt;
        }
        return args;
    }

    /** datetime(6) 은 UTC 벽시계로 담는다(hibernate.jdbc.time_zone=UTC 와 같은 값). 마이크로초로 잘라 DB 의 반올림에 맡기지 않는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
