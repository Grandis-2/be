package com.grandis.nova.member.support;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 테스트가 쓰는 MySQL · Redis. JVM 하나에 한 벌만 띄우고 끝까지 같이 쓴다(Testcontainers 가 JVM 종료 때 치운다).
 *
 * 스키마는 **운영에 쓰는 그 마이그레이션**으로 만든다. `ddl-auto: validate` 가 실제 배포 스키마와 대조되므로
 * 마이그레이션이 엔티티와 어긋나면 테스트가 잡는다.
 *
 * 마이그레이션을 스프링이 아니라 여기서 한 번 돌리는 이유: member 에는 자기 애플리케이션을 직접 띄우는 시험
 * (DdlValidateProbeTest)과 스프링 없이 Redis 만 쓰는 시험이 있다. 스프링에 맡기면 그 시험들이 뜨는 순서에 따라
 * 스키마가 있기도 없기도 한다. 여기서 한 번 만들어 두면 모든 시험이 같은 스키마를 본다.
 *
 * 컨테이너를 스프링 빈으로 두지 않는 것도 같은 이유다 — 빈으로 두면 컨텍스트가 닫힐 때 같이 멈춰
 * 뒤에 오는 시험이 죽은 컨테이너를 보게 된다.
 */
public final class Containers {

    /** 운영과 같은 8.4. 잠금·CHECK·UNIQUE 동작은 엔진마다 달라 H2 로는 못 잰다. */
    static final String MYSQL_IMAGE = "mysql:8.4";
    static final String REDIS_IMAGE = "redis:8";
    private static final int REDIS_PORT = 6379;

    public static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE).withDatabaseName("shop");
    public static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(REDIS_PORT);

    static {
        MYSQL.start();
        REDIS.start();
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:" + migrationsPath())
                .defaultSchema("shop")
                .schemas("shop")
                .createSchemas(false)
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load()
                .migrate();
    }

    private Containers() {
    }

    /** 빌드가 절대 경로로 넘긴다(member/build.gradle). 모듈 작업 디렉터리에 기대지 않는다. */
    private static String migrationsPath() {
        String path = System.getProperty("nova.migrations-path");
        if (path == null) {
            throw new IllegalStateException("nova.migrations-path 가 없습니다. gradle test 로 실행하세요(member/build.gradle).");
        }
        return path;
    }

    public static String redisHost() {
        return REDIS.getHost();
    }

    public static int redisPort() {
        return REDIS.getMappedPort(REDIS_PORT);
    }

    /** 스프링 밖에서 컨테이너를 쓰는 시험용(자기 애플리케이션을 직접 띄우는 경우). */
    public static String[] datasourceProperties() {
        return new String[] {
                "spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "spring.datasource.username=" + MYSQL.getUsername(),
                "spring.datasource.password=" + MYSQL.getPassword()
        };
    }
}
