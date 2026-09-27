package com.grandis.nova.catalog.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * 시험 전용. 지정한 문자열이 든 SQL 이 실행되기 직전에 훅을 한 번 돌린다 — "문장 사이에 다른 커넥션의 커밋이 끼는" 틈을 만든다.
 * Hibernate 가 내는 SQL 만 본다(JdbcTemplate 은 거치지 않는다).
 */
public class SqlHookInspector implements StatementInspector {

    static volatile String needle;
    static volatile Runnable hook;

    @Override
    public String inspect(String sql) {
        Runnable pending = hook;
        String target = needle;
        if (pending != null && target != null && sql.contains(target)) {
            hook = null;
            pending.run();
        }
        return sql;
    }

    public static void before(String sqlContains, Runnable runnable) {
        needle = sqlContains;
        hook = runnable;
    }

    public static void reset() {
        needle = null;
        hook = null;
    }
}
