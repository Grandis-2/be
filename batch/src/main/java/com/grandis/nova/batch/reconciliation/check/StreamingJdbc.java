package com.grandis.nova.batch.reconciliation.check;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * be 내부 확인할 것의 조회를 한 행씩 받는다. MySQL 드라이버는 결과를 전부 메모리에 받아 둔 뒤 넘기는데, fetch size 를
 * Integer.MIN_VALUE 로 주면 한 행씩 흘려 준다(Connector/J 스트리밍). 불일치 상한은 모을 결과만 막으므로, 어긋난 행이 예약 수만큼
 * 나올 수 있는 조회는 여기로 읽어 조회 결과도 메모리에 다 올리지 않는다. 연결은 검사 트랜잭션의 것을 그대로 쓴다(같은 스냅샷).
 *
 * 결과를 다 읽거나 닫기 전에는 같은 연결로 다른 조회를 못 한다 — 행을 받는 동안 DB 를 부르지 않는 조회에만 쓴다. be 예약은
 * 맞대는 도중 키 확인 조회가 있어 청크로 읽는다(BeSnapshot). JdbcClient 빈을 하나 더 두면 자동설정 JdbcClient 가 빠지므로 감싼다.
 */
@Component
class StreamingJdbc {

    private final JdbcClient jdbc;

    StreamingJdbc(DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setFetchSize(Integer.MIN_VALUE);
        this.jdbc = JdbcClient.create(template);
    }

    JdbcClient.StatementSpec sql(String sql) {
        return jdbc.sql(sql);
    }
}
