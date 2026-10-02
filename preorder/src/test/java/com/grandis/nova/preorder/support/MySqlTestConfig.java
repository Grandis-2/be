package com.grandis.nova.preorder.support;

import com.grandis.nova.preorder.support.containers.MySqlTestContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 컨텍스트 밖의 싱글턴 MySQL 에 접속 정보만 잇는다. 컨테이너를 빈으로 두지 않는 것은
 * 빈이면 컨텍스트가 캐시에서 밀려나 닫힐 때 함께 멈춰 다른 컨텍스트가 끊기기 때문이다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestConfig {

    @Bean
    DynamicPropertyRegistrar mysqlProperties() {
        MySQLContainer mysql = MySqlTestContainer.get();
        return registry -> {
            registry.add("spring.datasource.url", mysql::getJdbcUrl);
            registry.add("spring.datasource.username", mysql::getUsername);
            registry.add("spring.datasource.password", mysql::getPassword);
        };
    }
}
