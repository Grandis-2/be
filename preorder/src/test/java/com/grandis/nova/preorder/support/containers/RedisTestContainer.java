package com.grandis.nova.preorder.support.containers;

import org.testcontainers.containers.GenericContainer;

/** JVM 에 하나뿐인 Redis(싱글턴 컨테이너). 토큰 폐기 확인이 읽는다. CI 와 같은 이미지다. */
public final class RedisTestContainer {

    public static final int PORT = 6379;

    private static final GenericContainer<?> INSTANCE = start();

    private RedisTestContainer() {
    }

    public static GenericContainer<?> get() {
        return INSTANCE;
    }

    private static GenericContainer<?> start() {
        GenericContainer<?> container = new GenericContainer<>("redis:8").withExposedPorts(PORT);
        container.start();
        return container;
    }
}
