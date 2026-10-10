package com.grandis.nova.order.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 운영과 같은 MySQL 8.4. 테이블은 Flyway 가 만든다({@link OrderIntegrationTest}).
 *
 * 시험 JVM 하나에 컨테이너 하나를 모든 Spring 컨텍스트가 함께 쓴다. 컨텍스트마다 띄우면 캐시된 컨텍스트 수만큼(실측 18개) 컨테이너가 동시에 떠
 * Docker VM 메모리가 모자라고, OOM 으로 죽은 컨테이너를 쓰던 시험이 무작위로 실패했다.
 *
 * 컨테이너를 Spring 빈으로 두지 않는다 — 빈이면 그 컨텍스트가 닫힐 때(@DirtiesContext) Boot 가 컨테이너를 멈추고, 다음 컨텍스트가 새 포트로 다시
 * 띄워 이미 캐시된 다른 컨텍스트들의 연결이 끊긴다(destroyMethod 를 비워도 멈췄다 — 실측). 처음 쓸 때 한 번 띄우고, 끄는 것은 시험 JVM 이 끝날 때
 * Testcontainers 가 한다. 접속 정보만 컨텍스트마다 넘긴다.
 *
 * 컨텍스트끼리 DB 를 함께 쓰므로 시험은 자기가 만든 행(회원 · 주문 id)으로만 단언한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainerConfig {

    static final String IMAGE = "mysql:8.4";

    /**
     * 연결 상한을 올린다 — 컨텍스트마다 연결 풀(Hikari 기본 10)을 따로 들고 같은 DB 에 붙어, 기본 151 이면 컨텍스트 20개 남짓에서
     * "Too many connections" 로 뒤 컨텍스트가 뜨지 못한다(실측).
     */
    static final int MAX_CONNECTIONS = 1000;

    private static final MySQLContainer SHARED = new MySQLContainer(IMAGE).withDatabaseName("shop")
            .withCommand("--max_connections=" + MAX_CONNECTIONS);

    static {
        SHARED.start();
    }

    @Bean
    DynamicPropertyRegistrar mysqlConnection() {
        return registry -> {
            registry.add("spring.datasource.url", SHARED::getJdbcUrl);
            registry.add("spring.datasource.username", SHARED::getUsername);
            registry.add("spring.datasource.password", SHARED::getPassword);
        };
    }
}
