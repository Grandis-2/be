package com.grandis.nova.common.redis.cache;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;

/**
 * 읽기 캐시 시험이 함께 쓰는 Redis 준비.
 *
 * <p>시험 대상 캐시는 운영 예시 설정과 같은 300ms 제한 연결로 Redis 를 쓴다. 각 시험의 {@code @BeforeEach} 준비(비우기 · 세대 심기)까지 그
 * 연결로 보내면, CI 가 잠깐 느릴 때 준비가 시간 초과로 깨져 시험이 시작도 못 한다. 그래서 {@code @BeforeEach} 의 준비만
 * {@link #fixtureConnections} 의 넉넉한 제한 연결로 보낸다. 시험 본문의 직접 조작(값 망가뜨리기 · 세대 지우기 등)은 300ms 연결 그대로다 —
 * 저장을 꺼 그 비용이 시험 대상 캐시 호출과 같은 크기라, 옮겨도 위험이 거의 줄지 않는다.
 */
final class TestRedis {

    /** 준비 연결의 제한 시간. 서버가 잠깐 멈춰도 준비가 기다렸다 끝나게 크게 잡는다. */
    private static final Duration FIXTURE_TIMEOUT = Duration.ofSeconds(10);

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

    /** 시험 준비용 연결. 쓰고 나면 {@link LettuceConnectionFactory#destroy()} 로 닫는다. */
    static LettuceConnectionFactory fixtureConnections(GenericContainer<?> redis) {
        LettuceConnectionFactory connections = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)),
                LettuceClientConfiguration.builder().commandTimeout(FIXTURE_TIMEOUT).build());
        connections.afterPropertiesSet();
        connections.start();
        return connections;
    }
}
