package com.grandis.nova.common.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 다시 시도하는 갈래. 실제 Redis 로는 "첫 시도만 실패" 를 만들기 어려워 팩토리 대역으로 본다. */
@ExtendWith(OutputCaptureExtension.class)
class RedisWarmupTest {

    final RedisConnectionFactory connections = mock(RedisConnectionFactory.class);
    final RedisConnection connection = mock(RedisConnection.class);

    @Test
    @DisplayName("첫 시도가 실패하면 다시 해 PING 이 되면 완료 — INFO 에 시도 횟수와 앞 시도의 원인이 남는다")
    void retriesAfterFirstFailure(CapturedOutput output) {
        given(connections.getConnection())
                .willThrow(new RedisConnectionFailureException("Unable to connect to Redis", new TimeoutException("handshake 300ms")))
                .willReturn(connection);

        new RedisWarmup(connections, RedisWarmup.BUDGET).afterSingletonsInstantiated();

        verify(connection).ping();
        verify(connection).close();
        assertThat(output.getOut()).contains("Redis 연결 예열 완료", "attempts=2 ", "retriedAfter=", "handshake 300ms");
    }

    @Test
    @DisplayName("모두 실패하면 상한 횟수만큼만 시도하고 WARN 에 원인 예외의 이름과 메시지를 남긴다 — 던지지 않는다")
    void givesUpAfterMaxAttempts(CapturedOutput output) {
        given(connections.getConnection()).willReturn(connection);
        given(connection.ping()).willThrow(new RedisConnectionFailureException("down"));

        new RedisWarmup(connections, RedisWarmup.BUDGET).afterSingletonsInstantiated();

        verify(connections, times(RedisWarmup.MAX_ATTEMPTS)).getConnection();
        verify(connection, times(RedisWarmup.MAX_ATTEMPTS)).close();
        assertThat(output.getOut()).contains("Redis 연결 예열 실패", "attempts=" + RedisWarmup.MAX_ATTEMPTS + " ",
                RedisConnectionFailureException.class.getName() + ": down");
    }
}
