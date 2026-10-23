package com.grandis.nova.order.support;

import org.testcontainers.mysql.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 일반 로그로 한 호출이 서버에 보낸 문장을 받아 온다 — 트랜잭션 준비 · 정리 문장(autocommit · 격리 · 읽기 전용 전환)까지 보인다.
 * 표식 문장을 보낸 연결의 문장만 준다 — 같은 서버를 쓰는 다른 작업(아웃박스 릴레이 등)의 문장을 섞지 않는다. 트랜잭션을 열었다면 그 준비 문장도 같은 연결로 간다.
 * 트랜잭션 없이 여러 문장을 보내는 호출은 문장마다 풀에서 연결을 빌린다 — 같은 스레드에 마지막 연결을 다시 주는 Hikari 동작에 기대므로, 그 사이
 * 다른 스레드가 같은 연결을 빌리면 문장이 섞이거나 빠질 수 있다(시험이 실패하는 쪽이다).
 */
public final class GeneralLog {

    public interface Call {
        void run() throws Exception;
    }

    private GeneralLog() {
    }

    /** @param markerLike 이 호출이 보내는 문장 하나의 LIKE 모양 — 그 문장을 보낸 연결을 고른다 */
    public static List<String> statements(String markerLike, Call call) throws Exception {
        MySQLContainer mysql = MySqlContainerConfig.get();
        List<String> statements = new ArrayList<>();
        try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement statement = root.createStatement()) {
            statement.execute("SET GLOBAL log_output = 'TABLE'");
            statement.execute("SET GLOBAL general_log = 'ON'");
            statement.execute("TRUNCATE TABLE mysql.general_log");
            try {
                call.run();
            } finally {
                statement.execute("SET GLOBAL general_log = 'OFF'");
            }
            try (ResultSet rs = statement.executeQuery("SELECT argument FROM mysql.general_log WHERE command_type = 'Query' AND thread_id IN"
                    + " (SELECT thread_id FROM mysql.general_log WHERE argument LIKE '" + markerLike.replace("'", "''") + "') ORDER BY event_time")) {
                while (rs.next()) {
                    statements.add(new String(rs.getBytes(1), StandardCharsets.UTF_8));
                }
            }
        }
        return statements;
    }

    /** 트랜잭션 준비 · 정리 문장인가 — 이 문장이 보이면 그 호출이 트랜잭션을 열었다. */
    public static boolean isTransactionHousekeeping(String sql) {
        return sql.startsWith("SET autocommit") || sql.contains("TRANSACTION ISOLATION") || sql.contains("READ ONLY")
                || sql.contains("READ WRITE") || sql.equalsIgnoreCase("COMMIT");
    }
}
