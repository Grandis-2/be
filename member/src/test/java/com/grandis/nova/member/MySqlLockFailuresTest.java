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
    @DisplayName("getNextException 사슬에 1213 이 있어도 교착이다 — 방어(Connector/J 9.7 의 일괄 실행 오류는 cause 에 단다고 리뷰가 확인)")
    void followsNextExceptionChain() {
        BatchUpdateException batch = new BatchUpdateException("batch", "40001", 0, new int[0]);
        batch.setNextException(new SQLException("x", "40001", 1213));

        assertThat(MySqlLockFailures.isDeadlock(new CannotAcquireLockException("x", batch))).isTrue();
    }
}
