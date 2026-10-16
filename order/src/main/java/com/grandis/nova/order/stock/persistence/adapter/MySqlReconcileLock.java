package com.grandis.nova.order.stock.persistence.adapter;

import com.grandis.nova.order.stock.domain.repository.ReconcileLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * MySQL 이름 잠금(GET_LOCK). 연결에 묶이므로 트랜잭션을 요구한다(MANDATORY) — 트랜잭션이 연결을 쥐고 있어야 잡은 연결과 놓는 연결이 같다.
 * 인스턴스가 여럿이어도 같은 DB 를 보므로 하나만 잡는다. ShedLock 같은 표 기반 잠금은 두지 않는다 — 새벽 작업 하나라 이것으로 족하다.
 * 이름 잠금은 스키마가 아니라 DB 서버 전체에 하나라, 이름 앞에 스키마를 붙인다 — 한 서버의 다른 환경(스키마)끼리 서로 건너뛰게 하지 않는다.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class MySqlReconcileLock implements ReconcileLock {

    static final String NAME = "order-stock-reconcile";
    /** 스키마.이름. 기본 스키마가 없으면 NULL 이 되어 GET_LOCK 이 오류로 끝난다 — 조용히 서버 전체 이름을 쓰지 않는다. */
    private static final String SCOPED = "CONCAT(DATABASE(), '.', ?)";

    private final JdbcTemplate jdbc;

    MySqlReconcileLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** GET_LOCK 은 1(잡음) · 0(시간 초과 — 다른 연결이 쥐고 있다) · NULL(오류)을 돌려준다. */
    @Override
    public boolean tryLock() {
        Integer got = jdbc.queryForObject("SELECT GET_LOCK(" + SCOPED + ", 0)", Integer.class, NAME);
        if (got == null) {
            throw new IllegalStateException("이름 잠금을 잡다 오류: " + NAME);
        }
        return got == 1;
    }

    @Override
    public void unlock() {
        jdbc.queryForObject("SELECT RELEASE_LOCK(" + SCOPED + ")", Integer.class, NAME);
    }
}
