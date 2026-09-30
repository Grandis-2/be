package com.grandis.nova.catalog.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 기동할 때 실제 커넥션의 격리 수준이 READ COMMITTED 인지 확인하고, 아니면 기동을 멈춘다.
 *
 * 등록 재개는 "등록 기록을 잠금 읽기로 확인한 뒤 INSERT 또는 UPDATE" 할 예정이고, 같은 형태의 절차(preorder 접수)가
 * REPEATABLE READ(MySQL 기본값)에서 갭 락과 삽입 의도 락이 맞물려 교착하는 것을 preorder 에서 쟀다(RR 4/4 데드락, RC 4/4 통과).
 * catalog 의 절차로 다시 재지는 않았다 — 같은 모양이면 같은 결과라는 전제다.
 * 격리 수준은 커넥션 풀 설정(spring.datasource.hikari.transaction-isolation)으로 바꾸는데,
 * 빠뜨려도 오류가 나지 않고 부하가 걸릴 때까지 아무도 모른다. 그래서 설정값이 아니라 세션의 실제 값을 본다.
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
