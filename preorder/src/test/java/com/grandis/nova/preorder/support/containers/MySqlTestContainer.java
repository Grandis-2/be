package com.grandis.nova.preorder.support.containers;

import org.testcontainers.mysql.MySQLContainer;

/**
 * JVM 에 하나뿐인 MySQL 8.4(싱글턴 컨테이너). 처음 쓸 때 시작하고, 테스트 컨텍스트가 닫혀도 멈추지 않는다 —
 * JVM 이 끝나면 Testcontainers 가 정리한다. 컨텍스트마다 커넥션 풀이 붙으므로 최대 연결 수를 넉넉히 둔다.
 */
public final class MySqlTestContainer {

    private static final MySQLContainer INSTANCE = start();

    private MySqlTestContainer() {
    }

    public static MySQLContainer get() {
        return INSTANCE;
    }

    private static MySQLContainer start() {
        MySQLContainer container = new MySQLContainer("mysql:8.4")
                .withDatabaseName("shop")
                .withCommand("--max-connections=500");
        container.start();
        return container;
    }
}
