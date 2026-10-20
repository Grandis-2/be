package com.grandis.nova.common.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 자동설정 순서(DataRedisAutoConfiguration 다음)로 띄워 본다. 기동이 끝난 시점 = 컨텍스트 refresh 가 끝난 시점이다 —
 * 웹 서버는 그 안의 수명주기 시작에서 포트를 열므로, 여기서 이미 연결 · PING 이 끝나 있으면 첫 요청보다 앞이다.
 */
@ExtendWith(OutputCaptureExtension.class)
class RedisWarmupAutoConfigurationTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("valkey/valkey:9").withExposedPorts(6379);
    private static final String CLIENT_NAME = "warmup-test";

    /** 시험이 서버 상태를 들여다보는 별도 연결. 예열 대상 연결과 이름이 달라 세지 않는다. */
    static LettuceConnectionFactory inspector;

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class, RedisWarmupAutoConfiguration.class));

    @BeforeAll
    static void start() {
        REDIS.start();
        inspector = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        inspector.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        inspector.destroy();
        REDIS.stop();
    }

    /** 운영 예시 설정과 같은 제한 시간으로 띄운다. */
    private ApplicationContextRunner pointingAt(String host, int port) {
        return runner.withPropertyValues(
                "spring.data.redis.host=" + host,
                "spring.data.redis.port=" + port,
                "spring.data.redis.client-name=" + CLIENT_NAME,
                "spring.data.redis.timeout=300ms",
                "spring.data.redis.connect-timeout=200ms");
    }

    @Test
    @DisplayName("기동이 끝나면 공유 연결이 이미 열려 있고 PING 이 한 번 갔다 — 뒤의 명령은 새로 연결하지 않고 그 연결을 쓴다")
    void opensSharedConnectionBeforeStartupEnds(CapturedOutput output) {
        try (RedisConnection server = inspector.getConnection()) {
            server.serverCommands().resetConfigStats();

            pointingAt(REDIS.getHost(), REDIS.getMappedPort(6379)).run(ctx -> {
                assertThat(ctx).hasNotFailed().hasSingleBean(RedisWarmup.class);
                assertThat(warmedConnections(server)).isEqualTo(1);
                // RESP3(HELLO) 연결 준비는 PING 을 보내지 않는다 — 여기 PING 은 예열의 것뿐이다
                assertThat(server.serverCommands().info("commandstats").getProperty("cmdstat_ping")).startsWith("calls=1,");
                assertThat(output).contains("Redis 연결 예열 완료").contains("attempts=1 ");

                ctx.getBean(StringRedisTemplate.class).opsForValue().set("warmup", "ok");
                assertThat(warmedConnections(server)).isEqualTo(1);
            });
        }
    }

    @Test
    @DisplayName("Redis 가 연결을 거부해도 기동은 성공하고 WARN 만 남긴다 — 곧바로 실패하는 시도는 상한 횟수만큼만 다시 한다")
    void refusedConnectionDoesNotFailStartup(CapturedOutput output) throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        pointingAt("127.0.0.1", closedPort).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(RedisWarmup.class);
            assertThat(output).contains("Redis 연결 예열 실패").contains("attempts=" + RedisWarmup.MAX_ATTEMPTS + " ")
                    .contains("RedisConnectionFailureException");
        });
    }

    /**
     * 연결은 받지만 답하지 않는 서버. 제한 시간 설정이 없으면 Lettuce 기본값(60초)까지 기다리지만, 기동은 예열 상한만 기다리고 넘어간다.
     * 상한보다 짧게 끝나면 시도가 끊기지 않고 실패한 것이라 그 경우도 잡는다.
     */
    @Test
    @DisplayName("응답 없는 Redis 에도 기동은 예열 상한만큼만 기다리고 WARN 을 남긴 뒤 뜬다")
    void silentServerBoundsStartupByBudget(CapturedOutput output) throws IOException {
        try (ServerSocket silent = new ServerSocket(0)) {
            long started = System.nanoTime();

            runner.withPropertyValues("spring.data.redis.host=127.0.0.1", "spring.data.redis.port=" + silent.getLocalPort()).run(ctx -> {
                Duration startup = Duration.ofNanos(System.nanoTime() - started);
                assertThat(startup).isGreaterThanOrEqualTo(RedisWarmup.BUDGET).isLessThan(RedisWarmup.BUDGET.plusSeconds(5));
                assertThat(ctx).hasNotFailed();
                assertThat(output).contains("Redis 연결 예열 실패").contains("안에 끝나지 않아 그만둔다");
            });
        }
    }

    @Test
    @DisplayName("nova.redis.warmup.enabled=false 면 예열을 만들지 않는다")
    void disabledByProperty() {
        runner.withPropertyValues("nova.redis.warmup.enabled=false").run(ctx ->
                assertThat(ctx).hasNotFailed().doesNotHaveBean(RedisWarmup.class));
    }

    @Test
    @DisplayName("Redis 연결 팩토리가 없으면 예열을 만들지 않는다")
    void noConnectionFactory() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RedisWarmupAutoConfiguration.class)).run(ctx ->
                assertThat(ctx).hasNotFailed().doesNotHaveBean(RedisWarmup.class));
    }

    @Test
    @DisplayName("연결 팩토리가 둘이고 @Primary 가 없으면 예열이 물러나 기동이 실패하지 않는다")
    void twoConnectionFactories() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RedisWarmupAutoConfiguration.class))
                .withBean("first", LettuceConnectionFactory.class, LettuceConnectionFactory::new)
                .withBean("second", LettuceConnectionFactory.class, LettuceConnectionFactory::new)
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(RedisWarmup.class));
    }

    private static long warmedConnections(RedisConnection server) {
        return server.serverCommands().getClientList().stream().filter(c -> CLIENT_NAME.equals(c.getName())).count();
    }
}
