package com.grandis.nova.member;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import java.sql.BatchUpdateException;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlLockFailuresTest {

    @Test
    @DisplayName("교착(1213)은 원인 사슬의 벤더 코드로 — 대기 초과(1205)는 같은 Spring 예외 · SQLState 라도 교착이 아니다")
    void classifiesByVendorCodeInTheCauseChain() {
        assertThat(MySqlLockFailures.isDeadlock(new CannotAcquireLockException("x", new SQLException("x", "40001", 1213)))).isTrue();
        assertThat(MySqlLockFailures.isDeadlock(new CannotAcquireLockException("x", new SQLException("x", "40001", 1205)))).isFalse();
        assertThat(MySqlLockFailures.isDeadlock(new CannotAcquireLockException("x"))).isFalse();
    }

    @Test
    @DisplayName("일괄 실행 오류는 원인을 getNextException 에 단다 — 첫 SQLException 이 아니라 그 다음에 1213 이 있어도 교착이다")
    void followsNextExceptionChain() {
        BatchUpdateException batch = new BatchUpdateException("batch", "40001", 0, new int[0]);
        batch.setNextException(new SQLException("x", "40001", 1213));

        assertThat(MySqlLockFailures.isDeadlock(new CannotAcquireLockException("x", batch))).isTrue();
    }
}
