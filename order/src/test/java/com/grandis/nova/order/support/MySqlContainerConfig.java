package com.grandis.nova.order.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/** 운영과 같은 MySQL 8.4. 테이블은 Flyway 가 만든다({@link OrderIntegrationTest}). */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainerConfig {

    static final String IMAGE = "mysql:8.4";

    @Bean
    @ServiceConnection
    MySQLContainer mysql() {
        return new MySQLContainer(IMAGE).withDatabaseName("shop");
    }
}
