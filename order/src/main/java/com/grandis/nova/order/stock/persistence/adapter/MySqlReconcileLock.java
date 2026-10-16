package com.grandis.nova.order.stock.persistence.adapter;

import com.grandis.nova.order.stock.domain.repository.ReconcileLock;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * MySQL 이름 잠금(GET_LOCK). 이름 잠금은 트랜잭션이 아니라 연결에 묶이고 커밋 · 롤백으로 풀리지 않으므로, 일의 트랜잭션과 다른 연결 하나를
 * 풀에서 따로 받아 그 연결로 잡고 놓는다 — 잡기 → 일(트랜잭션 커밋까지) → 놓기 순서가 코드 그대로다(Flyway 가 PostgreSQL 세션 잠금에서
 * 잠금 연결과 마이그레이션 연결을 나누는 것과 같은 방식). 대조하는 동안 연결 두 개를 쓴다.
 * 인스턴스가 여럿이어도 같은 DB 를 보므로 하나만 잡는다. ShedLock 같은 표 기반 잠금은 두지 않는다 — 새벽 작업 하나라 이것으로 족하다.
 * 이름 잠금은 스키마가 아니라 DB 서버 전체에 하나라, 이름 앞에 스키마를 붙인다 — 한 서버의 다른 환경(스키마)끼리 서로 건너뛰게 하지 않는다.
 * 잡기 · 놓기가 확인되지 않으면 그 연결을 풀에서 내보낸다(Hikari evictConnection — 물리 연결을 닫아 서버가 잠금을 풀고, 풀은 새 연결로
 * 채운다). 그냥 돌려주면 잠금을 쥔 연결이 풀에 남아 다음 대조를 영영 막고, JDBC abort 는 풀에 반납되지 않아 슬롯이 샌다.
 * 남는 틈: 일하는 동안 잠금 연결만 끊기면 잠금이 먼저 풀려 다른 인스턴스가 한 번 더 돌 수 있다 — 결과는 기록 한 줄이 더 남는 것뿐이다.
 * {@code @Repository} 가 아니다 — 예외 변환 프록시가 work(도메인 코드)의 예외까지 바꾸지 않게. JDBC 예외는 여기서 직접 바꾼다.
 */
@Component
class MySqlReconcileLock implements ReconcileLock {

    private static final Logger log = LoggerFactory.getLogger(MySqlReconcileLock.class);

    static final String NAME = "order-stock-reconcile";
    /** 스키마.이름. 기본 스키마가 없으면 NULL 이 되어 GET_LOCK 이 오류로 끝난다 — 조용히 서버 전체 이름을 쓰지 않는다. */
    private static final String SCOPED = "CONCAT(DATABASE(), '.', ?)";

    private final HikariDataSource pool;

    MySqlReconcileLock(DataSource dataSource) {
        try {
            this.pool = dataSource.unwrap(HikariDataSource.class);
        } catch (SQLException e) {
            throw new IllegalStateException("대조 잠금은 Hikari 풀을 전제한다 — 놓기가 확인되지 않은 연결을 풀에서 내보내야 한다", e);
        }
    }

    @Override
    public <T> Optional<T> runExclusively(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("대조 잠금은 트랜잭션 밖에서 잡는다 — 안에서 잡으면 일의 커밋이 잠금을 놓은 뒤가 된다");
        }
        Connection connection = connect();
        // 잡기가 예외로 끝나면(응답 유실 — 잡았는지 모른다) 연결을 버린다
        boolean clean = false;
        try {
            if (!tryLock(connection)) {
                clean = true;
                return Optional.empty();
            }
            try {
                return Optional.of(work.get());
            } finally {
                clean = release(connection);
            }
        } finally {
            close(connection, clean);
        }
    }

    private Connection connect() {
        try {
            return pool.getConnection();
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("대조 잠금 연결을 얻지 못했다", e);
        }
    }

    /** GET_LOCK 은 1(잡음) · 0(시간 초과 — 다른 연결이 쥐고 있다) · NULL(오류)을 돌려준다. */
    private static boolean tryLock(Connection connection) {
        Integer got = queryInt(connection, "SELECT GET_LOCK(" + SCOPED + ", 0)");
        if (got == null) {
            throw new IllegalStateException("이름 잠금을 잡다 오류: " + NAME);
        }
        return got == 1;
    }

    /** 놓기 실패가 일의 원래 예외를 가리지 않게 한다. @return RELEASE_LOCK 이 1(놓음)을 돌려줬는가 */
    private static boolean release(Connection connection) {
        try {
            Integer released = queryInt(connection, "SELECT RELEASE_LOCK(" + SCOPED + ")");
            if (released != null && released == 1) {
                return true;
            }
            log.warn("재고 장부 대조 잠금 놓기가 확인되지 않음 — 연결을 풀에서 내보낸다 result={}", released);
        } catch (RuntimeException e) {
            log.warn("재고 장부 대조 잠금 놓기 실패 — 연결을 풀에서 내보낸다", e);
        }
        return false;
    }

    /** 잠금을 쥐지 않은 게 확실하면 풀에 돌려주고, 아니면 풀에서 내보내 물리 연결을 닫는다 — 서버가 그 세션의 잠금을 푼다. */
    private void close(Connection connection, boolean clean) {
        if (!clean) {
            pool.evictConnection(connection);
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("재고 장부 대조 잠금 연결 닫기 실패", e);
        }
    }

    private static Integer queryInt(Connection connection, String sql) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, NAME);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                int value = rs.getInt(1);
                return rs.wasNull() ? null : value;
            }
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("대조 잠금 문장 실패: " + sql, e);
        }
    }
}
