package com.grandis.nova.common.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 다시 시도 · 상한 초과 · 시작 전 팩토리 갈래. 실제 Redis 로는 "첫 시도만 실패" · "늦게 끝나는 시도" 를 만들기 어려워 팩토리 대역으로 본다. */
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
                RedisConnectionFailureException.class.getName() + ": down", "\tat ");
    }

    @Test
    @DisplayName("상한을 넘기면 기동은 기다리기를 그만두되, 진행 중이던 시도는 끊지 않고 그 실패 원인을 스택과 함께 남긴다 — 새 시도는 없다")
    void logsLateFailureAfterBudget(CapturedOutput output) throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        given(connections.getConnection()).willAnswer(invocation -> {
            release.await();
            throw new RedisConnectionFailureException("Unable to connect to Redis", new TimeoutException("handshake 300ms"));
        });

        new RedisWarmup(connections, Duration.ofMillis(100)).afterSingletonsInstantiated();
        assertThat(output.getOut()).contains("안에 끝나지 않아 그만둔다").doesNotContain("그만둔 뒤 시도가 실패로 끝났다");

        release.countDown();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!output.getOut().contains("그만둔 뒤 시도가 실패로 끝났다") && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(output.getOut()).contains("그만둔 뒤 시도가 실패로 끝났다", "attempts=1 ", "handshake 300ms", "\tat ");
        verify(connections, times(1)).getConnection();
    }

    @Test
    @DisplayName("연결 팩토리가 아직 시작 전이면(earlyStartup 끔) 연결하지 않고 건너뛰며 WARN 을 남긴다")
    void skipsWhenFactoryNotStarted(CapturedOutput output) {
        LettuceConnectionFactory notStarted = new LettuceConnectionFactory();

        new RedisWarmup(notStarted, RedisWarmup.BUDGET).afterSingletonsInstantiated();

        assertThat(notStarted.isRunning()).isFalse();
        assertThat(output.getOut()).contains("Redis 연결 예열을 건너뛴다", "아직 시작 전이다");
    }
}
