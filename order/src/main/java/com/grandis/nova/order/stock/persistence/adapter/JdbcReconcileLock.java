package com.grandis.nova.order.stock.persistence.adapter;

import com.grandis.nova.order.stock.domain.repository.ReconcileLock;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.List;

/**
 * 잠금 표(stock_reconcile_lock)의 한 행을 대조 트랜잭션 안에서 FOR UPDATE NOWAIT 로 잠근다. 행 잠금은 트랜잭션에 묶이므로 대조와 같은 연결 ·
 * 같은 생명주기다 — 연결이 끊기면 대조 트랜잭션도 함께 롤백되어 기록을 남기지 못한다. 이름 잠금(GET_LOCK)은 연결에 묶여 이렇게 묶을 수 없었다.
 * 쥔 행을 만나면 MySQL 은 기다리지 않고 3572 로 끝낸다 — 문장만 실패하고 트랜잭션은 그대로다.
 * {@code @Repository} 가 아니다 — JDBC 예외는 JdbcTemplate 이 바꾸고, 예외 변환 프록시가 사용 오류(IllegalStateException)의 타입까지 바꾸지 않게.
 */
@Component
class JdbcReconcileLock implements ReconcileLock {

    /** ER_LOCK_NOWAIT */
    static final int MYSQL_LOCK_NOWAIT = 3572;

    private static final String LOCK = "SELECT id FROM stock_reconcile_lock WHERE id = 1 FOR UPDATE NOWAIT";

    private final JdbcTemplate jdbc;

    JdbcReconcileLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryLock() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("대조 잠금은 대조 트랜잭션 안에서 잡는다 — 밖에서 잡으면 문장이 끝나며 바로 풀린다");
        }
        List<Integer> rows;
        try {
            rows = jdbc.queryForList(LOCK, Integer.class);
        } catch (DataAccessException e) {
            if (isLockNowait(e)) {
                return false;
            }
            throw e;
        }
        if (rows.isEmpty()) {
            // 지운 쪽(초기화 스크립트 등)을 고친다 — 여기서 넣으면 동시에 넣는 인스턴스가 앞 대조가 끝날 때까지 기다린다
            throw new IllegalStateException("대조 잠금 행(stock_reconcile_lock id=1)이 없다 — 마이그레이션이 넣는 행이다");
        }
        return true;
    }

    private static boolean isLockNowait(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == MYSQL_LOCK_NOWAIT) {
                return true;
            }
        }
        return false;
    }
}
