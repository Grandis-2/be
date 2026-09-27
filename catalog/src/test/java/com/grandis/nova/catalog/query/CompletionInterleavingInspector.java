package com.grandis.nova.catalog.query;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 시험 전용. 실행되는 SQL 을 모으고, 등록 기록 표를 읽는 SQL 직전에 훅을 한 번 돌린다 —
 * "읽기 사이에 완료 트랜잭션이 커밋되는" 틈을 만든다.
 * 훅은 등록 기록을 읽는 첫 문장 앞에 걸리므로 "등록을 먼저 따로 읽는" 구현의 틈에는 못 들어간다. 그 방향은 모아 둔 SQL 의
 * 모양(등록 기록을 읽는 문장이 하나이고 products 도 함께 읽는가)으로 잡는다.
 */
public class CompletionInterleavingInspector implements StatementInspector {

    static volatile Runnable beforeRegistrationRead;
    static final List<String> executed = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        executed.add(sql);
        Runnable hook = beforeRegistrationRead;
        if (hook != null && sql.contains("product_registrations")) {
            beforeRegistrationRead = null;
            hook.run();
        }
        return sql;
    }

    static void reset() {
        beforeRegistrationRead = null;
        executed.clear();
    }
}
