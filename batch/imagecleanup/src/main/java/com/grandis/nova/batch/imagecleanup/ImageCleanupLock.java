package com.grandis.nova.batch.imagecleanup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 사진 정리를 한 번에 하나만 돌린다 — MySQL 이름 잠금(GET_LOCK). 같은 이름은 한 연결만 쥐고, 못 잡으면 이번 실행을 건너뛴다.
 * 사진 정리는 하나만 띄우지만 배포 중에는 옛 태스크와 새 태스크가 잠깐 함께 돌 수 있다. 겹쳐도 행 삭제가 조건부라 데이터는 맞지만 같은
 * 대상을 두 번 고르고 결과 로그가 둘로 나뉜다.
 *
 * 잠금은 잡은 연결(세션)에 붙는다 — 실행 동안 그 연결을 따로 쥐고, 끝나면 놓은 뒤 풀에 돌려준다. 정리의 조회 · 삭제는 다른 연결로 한다.
 * 프로세스가 죽으면 연결이 끊겨 MySQL 이 잠금을 푼다 — 실행 표식 테이블처럼 끝나지 않은 실행을 정리할 일이 없다.
 */
@Component
class ImageCleanupLock {

    /** 이름 잠금은 DB 서버 전체에서 하나다 — 스키마 이름으로 나뉘지 않아 앞에 nova 를 붙인다 */
    static final String NAME = "nova.batch.image-cleanup";

    private static final Logger log = LoggerFactory.getLogger(ImageCleanupLock.class);

    private final JdbcTemplate jdbc;

    ImageCleanupLock(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 잠금을 잡으면 work 를 돌리고 true, 다른 연결이 쥐고 있으면 돌리지 않고 false. work 의 예외는 잠금을 놓은 뒤 그대로 나간다 */
    boolean runExclusively(Runnable work) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            if (!acquire(connection)) {
                return false;
            }
            try {
                work.run();
                return true;
            } finally {
                release(connection);
            }
        }));
    }

    /** 1 이면 잡았다. 0(다른 연결이 쥠) · NULL(오류)은 못 잡았다 — getInt 는 NULL 을 0 으로 읽는다 */
    private static boolean acquire(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, NAME);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    /**
     * 놓지 못해도 실행 결과는 그대로 둔다. 연결이 끊겨서라면 MySQL 이 이미 풀었고, 아니면 풀이 연결을 은퇴시킬 때(최대 수명) 풀린다.
     */
    private static void release(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, NAME);
            statement.executeQuery().close();
        } catch (SQLException e) {
            log.warn("사진 정리 잠금을 놓지 못했다", e);
        }
    }
}
