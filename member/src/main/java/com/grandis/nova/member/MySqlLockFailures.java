package com.grandis.nova.member;

import java.sql.SQLException;

/**
 * MySQL 잠금 실패 가르기. 재사용으로 판정된 세션의 체인 폐기가 교착이면 다시 하고,
 * 잠금 대기 초과면 다시 하지 않는다. order 와 같은 판정이다(모듈 간 공유 금지라 복사).
 */
public final class MySqlLockFailures {

    /** ER_LOCK_DEADLOCK */
    public static final int MYSQL_DEADLOCK = 1213;

    private MySqlLockFailures() {
    }

    /**
     * MySQL 교착(1213)인가. 원인 사슬의 벤더 코드로 가른다 — 1213 과 잠금 대기 초과 1205 는 같은 Spring 예외
     * (CannotAcquireLockException)와 같은 SQLState(40001)로 올라와 예외 클래스로도 SQLState 로도 못 가른다
     * (order 의 LockFailureClassificationTest 가 진짜 오류로 확인).
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
