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
 * catalog 의 잠금 · 경합 시험(수정의 상품 행 잠금 · 교착 재시도 · 아웃박스 릴레이의 SKIP LOCKED 등)은 전부 READ COMMITTED 에서 잰 것이라
 * (CatalogIntegrationTest), 운영도 같은 격리 수준이어야 그 결과가 유효하다. 팀 공통 근거로는 "없는 행을 잠금 읽기로 확인한 뒤 INSERT" 하는
 * 절차가 REPEATABLE READ(MySQL 기본값)에서 교착하는 것을 preorder 가 쟀다(RR 4/4 데드락, RC 4/4 통과). 처음 이 클래스의 근거였던 catalog 등록
 * 재개 절차는 이벤트 방식으로 바뀌며 없어졌다(2026-10-02).
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
