package com.grandis.nova.member.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.member.support.Containers;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 알림 id(v7)는 INSERT 의 DB 연결을 얻은 뒤에 만든다 — 연결 대기가 id 시각과 커밋 사이에 끼면 새 알림 읽기(NotificationTail)의 겹침 창을 넘어
 * 그 행을 놓친다. 연결을 늦게 주는 데이터소스로 본다(실제 MySQL). 중복 막기 · 거절은 NotificationReceiverTest 가 본다.
 */
@DisplayName("알림 저장 — id 는 DB 연결을 얻은 뒤에 만든다 (실제 MySQL)")
class NotificationWriterTest {

    static final long CONNECTION_DELAY_MILLIS = 300;

    @Test
    @DisplayName("연결을 기다린 시간이 id 시각과 커밋 사이에 끼지 않는다 — id 시각은 연결을 받은 뒤다")
    void idIsGeneratedAfterConnectionIsAcquired() {
        SlowDataSource dataSource = new SlowDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID customer = UUID.randomUUID();
        NotificationRequested request = new NotificationRequested(List.of(customer), "TEST", "제목", "본문",
                new NotificationRequested.Target("PREORDER", "x"));
        String eventId = UUID.randomUUID().toString();

        int stored = new NotificationWriter(jdbc).store(
                new NotificationWriter.Incoming(eventId, "PREORDER", null, Instant.now(), request, Instant.now()));

        assertThat(stored).isEqualTo(1);
        UUID id = UuidBinary.fromBytes(jdbc.queryForObject("SELECT id FROM notifications WHERE event_id = ?", byte[].class, eventId));
        // 저장은 연결을 두 번 받는다(이미 넣은 회원 읽기 → INSERT). 두 번째가 INSERT 의 연결이다
        assertThat(NotificationTail.timeOf(id)).isAfterOrEqualTo(dataSource.handedOut.get(1).truncatedTo(ChronoUnit.MILLIS));
    }

    /** 연결을 줄 때마다 일부러 늦게 주고, 넘긴 시각을 적는다. */
    static final class SlowDataSource extends DelegatingDataSource {

        final List<Instant> handedOut = new CopyOnWriteArrayList<>();

        SlowDataSource() {
            super(new DriverManagerDataSource(Containers.MYSQL.getJdbcUrl(), Containers.MYSQL.getUsername(), Containers.MYSQL.getPassword()));
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            try {
                Thread.sleep(CONNECTION_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            handedOut.add(Instant.now());
            return connection;
        }
    }
}
