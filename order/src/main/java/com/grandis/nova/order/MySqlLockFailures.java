package com.grandis.nova.order;

import java.sql.SQLException;

/** MySQL 잠금 실패 가르기. 재고 변경 · 장바구니 담기가 교착이면 새 트랜잭션에서 다시 하고, 잠금 대기 초과면 다시 하지 않는다. */
public final class MySqlLockFailures {

    /** ER_LOCK_DEADLOCK */
    public static final int MYSQL_DEADLOCK = 1213;

    private MySqlLockFailures() {
    }

    /**
     * MySQL 교착(1213)인가. 원인 사슬의 벤더 코드로 가른다 — 1213 과 잠금 대기 초과 1205 는 같은 Spring 예외
     * (CannotAcquireLockException)와 같은 SQLState(40001)로 올라와 예외 클래스로도 SQLState 로도 못 가른다
     * (LockFailureClassificationTest 가 진짜 오류로 확인).
     */
    public static boolean isDeadlock(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            // 일괄 실행(BatchUpdateException 등)은 원인을 getNextException 사슬에 단다
            for (SQLException sql = t instanceof SQLException s ? s : null; sql != null; sql = sql.getNextException()) {
                if (sql.getErrorCode() == MYSQL_DEADLOCK) {
                    return true;
                }
            }
        }
        return false;
    }
}
