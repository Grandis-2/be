package com.grandis.nova.batch.imagecleanup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 사진 정리를 한 번에 하나만 돌린다 — MySQL 이름 잠금(GET_LOCK). 같은 이름은 한 연결만 쥐고, 못 잡으면 이번 실행을 건너뛴다.
 * 사진 정리는 Scheduler 가 하루 한 번 띄우지만 손으로 띄운 태스크와 겹칠 수 있다. 겹쳐도 행 삭제가 조건부라 데이터는 맞지만 같은
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

    /** 잠금을 잡으면 work 를 돌려 그 결과를, 다른 연결이 쥐고 있으면 돌리지 않고 빈 값을 돌려준다. work 의 예외는 잠금을 놓은 뒤 그대로 나간다 */
    <T> Optional<T> runExclusively(Supplier<T> work) {
        return jdbc.execute((ConnectionCallback<Optional<T>>) connection -> {
            if (!acquire(connection)) {
                return Optional.empty();
            }
            try {
                return Optional.of(work.get());
            } finally {
                release(connection);
            }
        });
    }

    /**
     * 1 이면 잡았고 0 이면 다른 연결이 쥐고 있다. NULL(잠금 오류) · 결과 없음은 던진다 — 건너뛴 것처럼 0 으로 끝나면 정리가 돌지 않았는데도
     * 경보가 울리지 않는다.
     */
    private static boolean acquire(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, NAME);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("사진 정리 잠금(GET_LOCK)이 결과를 돌려주지 않았다");
                }
                int result = rs.getInt(1);
                if (rs.wasNull()) {
                    throw new IllegalStateException("사진 정리 잠금(GET_LOCK)이 오류(NULL)를 돌려줬다");
                }
                return result == 1;
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
