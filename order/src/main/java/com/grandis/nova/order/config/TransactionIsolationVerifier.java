package com.grandis.nova.order.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 커넥션의 실제 격리 수준이 READ COMMITTED 가 아니면 기동을 멈춘다.
 *
 * MySQL 기본값 REPEATABLE READ 에서는 갭 락 때문에 잠금 읽기 후 INSERT 가 교착한다(preorder 실측 RR 4/4 데드락).
 * 설정(hikari.transaction-isolation)을 빠뜨려도 오류가 없으므로 세션 값을 직접 확인한다.
 */
@Component
public class TransactionIsolationVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TransactionIsolationVerifier.class);

    static final String REQUIRED = "READ-COMMITTED";

    private final JdbcTemplate jdbcTemplate;

    public TransactionIsolationVerifier(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        String actual = jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class);
        if (!REQUIRED.equals(actual)) {
            throw new IllegalStateException(
                    "트랜잭션 격리 수준이 %s 이어야 하는데 %s 입니다. spring.datasource.hikari.transaction-isolation 을 "
                            .formatted(REQUIRED, actual)
                            + "TRANSACTION_READ_COMMITTED 로 설정하세요.");
        }
        log.info("트랜잭션 격리 수준 확인: {}", actual);
    }
}
