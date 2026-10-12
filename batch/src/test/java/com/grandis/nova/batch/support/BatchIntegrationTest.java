package com.grandis.nova.batch.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 통합 시험의 바탕 — 싱글턴 MySQL(마이그레이션 적용)과 가짜 Mock 원장에 붙는다. 시험마다 결과 · 업무 행과 가짜 Mock 을 비운다.
 * 주기 작업은 꺼져 있어(test 프로필) 시험이 ReconciliationService 를 직접 부른다.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class BatchIntegrationTest {

    protected static final FakeMockLedger MOCK = FakeMockLedger.start();

    @Autowired
    protected JdbcClient jdbc;

    protected TestData data;

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry registry) {
        MySQLContainer mysql = MySqlTestContainer.get();
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("external.mock.base-url", MOCK::url);
    }

    @BeforeEach
    void clearData() {
        data = new TestData(jdbc);
        data.clear();
        MOCK.reset();
    }
}
