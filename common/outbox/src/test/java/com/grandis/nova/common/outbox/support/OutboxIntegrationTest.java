package com.grandis.nova.common.outbox.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.mysql.MySQLContainer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MySQL 8.4 위에서 도는 통합 시험. 표는 시험 전용 DDL(outbox-test-schema.sql)로 만든다.
 * 운영과 같아야 하는 값(격리 수준 READ COMMITTED · ddl-auto validate · UTC)을 여기서 준다.
 * 전송은 로그 전송기(명시 허용 포함)이고, 릴레이는 시험이 직접 부른다 — 주기 실행이 끼어들면 잠그는 행이 겹친다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(classes = OutboxTestApplication.class, properties = {
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:outbox-test-schema.sql",
        "nova.outbox.transport=log",
        "nova.outbox.log-transport-allowed=true",
        "nova.outbox.relay-interval=1h"
})
@Import(OutboxIntegrationTest.MySql.class)
public @interface OutboxIntegrationTest {

    /** 컨텍스트 밖의 싱글턴 MySQL 에 접속 정보만 잇는다 — 컨테이너를 빈으로 두면 컨텍스트가 닫힐 때 함께 멈춘다. */
    @TestConfiguration(proxyBeanMethods = false)
    class MySql {

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
}
