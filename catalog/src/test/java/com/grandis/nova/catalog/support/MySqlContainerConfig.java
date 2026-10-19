package com.grandis.nova.catalog.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 운영과 같은 MySQL 8.4 — JVM 에 하나뿐인 싱글턴 컨테이너에 접속 정보만 잇는다(preorder MySqlTestConfig 선례). 빈 shop 데이터베이스만 만들고,
 * 테이블은 컨텍스트가 뜰 때 Flyway 가 마이그레이션으로 만든다(처음 뜬 컨텍스트가 만들고, 다음 컨텍스트는 이미 적용됨으로 지나간다).
 *
 * 컨테이너를 컨텍스트마다의 빈으로 두면 시험 설정이 다른 컨텍스트마다 MySQL 이 하나씩 떴다 — 관측(2026-10-19, 로컬 catalog 전체 시험, 다른
 * 세션의 시험과 겹친 Docker): 2분 사이 MySQL 8개 · Docker 메모리 8GB 소진, 뒤의 컨텍스트가 연결을 못 얻었다(DiscountSqlTest 의
 * CannotGetJdbcConnectionException · 컨텍스트 실패가 전체 실행에서만 난 까닭). 싱글턴으로 바꾼 뒤 리뷰 실측: 이 JVM 의 MySQL 은 싱글턴 하나(그
 * 밖에는 자체 컨테이너를 띄우는 migration/ 시험뿐), Max_used_connections 최대 221. 컨텍스트마다 커넥션 풀이 이 서버 하나에 붙으므로 최대 연결 수를
 * 넉넉히 둔다(컨텍스트 캐시 32 × 풀 10 보다 크게).
 * 표를 모든 컨텍스트가 같이 쓴다 — 아웃박스 릴레이를 짧게 켠 시험(CatalogOutboxWiringTest)은 다른 시험이 남긴 미발행 행도 집어 발행 완료로 적는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainerConfig {

    /** verify.sh(CI 의 마이그레이션 검증)와 같은 버전으로 고정한다. 8.4 안에서도 마이너가 다르면 동작이 갈릴 수 있다. */
    static final String IMAGE = "mysql:8.4.11";

    private static final MySQLContainer INSTANCE = start();

    /** 싱글턴 컨테이너 — root 연결(통계 · 잠금 대기 관찰)이 필요한 시험이 쓴다. */
    public static MySQLContainer get() {
        return INSTANCE;
    }

    private static MySQLContainer start() {
        MySQLContainer container = new MySQLContainer(IMAGE).withDatabaseName("shop").withCommand("--max-connections=500");
        container.start();
        return container;
    }

    @Bean
    DynamicPropertyRegistrar mysqlProperties() {
        return registry -> {
            registry.add("spring.datasource.url", INSTANCE::getJdbcUrl);
            registry.add("spring.datasource.username", INSTANCE::getUsername);
            registry.add("spring.datasource.password", INSTANCE::getPassword);
        };
    }
}
