package com.grandis.nova.catalog.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 시험 전용. 지정한 문자열이 든 SQL 이 실행되기 직전에 훅을 한 번 돌린다 — "문장 사이에 다른 커넥션의 커밋이 끼는" 틈을 만든다.
 * Hibernate 가 내는 SQL 만 본다(JdbcTemplate 은 거치지 않는다). 실행된 SQL 은 {@link #executed} 에 모은다 — 문장의 모양(JOIN 인가, 몇 문장인가)을 재는 데 쓴다.
 */
public class SqlHookInspector implements StatementInspector {

    static volatile String needle;
    static volatile Runnable hook;
    public static final List<String> executed = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        executed.add(sql);
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
        executed.clear();
    }
}
