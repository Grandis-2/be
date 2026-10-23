package com.grandis.nova.common.redis.cache;

import org.testcontainers.containers.GenericContainer;

/**
 * 읽기 캐시 시험이 함께 쓰는 Redis 준비.
 */
final class TestRedis {

    private TestRedis() {
    }

    /**
     * 저장(RDB)을 끈 Valkey. 기본 설정(save 3600 1 300 100 60 10000)에서는 FLUSHALL 마다 dump.rdb 를 동기로 디스크에 쓴다. 같은 이미지에서
     * FLUSHALL 100번이 모두 "DB saved on disk" 를 남겼고 한 번에 평균 1,816µs 였다. 저장을 끄면 디스크에 쓰지 않고 6.8µs 다. 시험은 다시
     * 띄운 뒤의 값을 읽지 않으므로 저장이 필요 없다.
     */
    static GenericContainer<?> container() {
        return new GenericContainer<>("valkey/valkey:9").withCommand("valkey-server", "--save", "").withExposedPorts(6379);
    }

}
