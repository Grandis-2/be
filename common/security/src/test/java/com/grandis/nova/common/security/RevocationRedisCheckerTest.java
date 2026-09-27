package com.grandis.nova.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 표식 없음 → 유효, sid 표식 → 거부, nbf ≥ iat → 거부, nbf < iat → 유효. 그리고 "MGET 에서 없는 키는 null 인가" 실측.
 *
 * 진짜 Redis(localhost:6379)로 돈다. 모킹하면 MGET 의 반환 형태와 장애 시 예외 종류를 못 잰다.
 * Redis 가 없으면 건너뛴다(CI 에 Redis 가 없을 때 "깨짐" 으로 오독하지 않게). 건너뛴 사실은 리포트에 skipped 로 남는다.
 */
@DisplayName("RevocationRedisChecker (실제 Redis)")
class RevocationRedisCheckerTest {

    private static final Instant IAT = Instant.parse("2026-09-19T10:00:00Z");

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static RevocationRedisChecker checker;

    private final UUID sid = UUID.randomUUID();
    private final String subject = "t-" + UUID.randomUUID();

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        boolean up;
        try {
            up = "PONG".equals(redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping()));
        } catch (RuntimeException e) {
            up = false;
        }
        if (!up) {
            TestInfra.unavailable("localhost:6379 에 Redis 가 없다");
        }
        checker = new RevocationRedisChecker(redis);
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @AfterEach
    void cleanUp() {
        redis.delete(List.of(AuthRedisKeys.revokedSession(sid), AuthRedisKeys.notBefore(subject)));
    }

    private TokenClaims claims() {
        return new TokenClaims(subject, sid, UUID.randomUUID(), Role.USER, TokenType.ACCESS, IAT, IAT.plusSeconds(3600));
    }

    @Test
    @DisplayName("실측: MGET 은 없는 키를 null 원소로 돌려준다 (크기는 요청한 키 수)")
    void multiGetReturnsNullForMissingKey() {
        redis.opsForValue().set(AuthRedisKeys.notBefore(subject), "1", Duration.ofSeconds(30));

        List<String> values = redis.opsForValue().multiGet(
                List.of(AuthRedisKeys.revokedSession(sid), AuthRedisKeys.notBefore(subject)));

        assertThat(values).hasSize(2);
        assertThat(values.get(0)).isNull();
        assertThat(values.get(1)).isEqualTo("1");
    }

    @Test
    @DisplayName("표식이 하나도 없으면 유효하다")
    void noMarksIsValid() {
        assertThat(checker.isRevoked(claims())).isFalse();
    }

    @Test
    @DisplayName("sid 폐기 표식이 있으면 거부한다 (로그아웃·재사용 탐지)")
    void revokedSessionIsRevoked() {
        redis.opsForValue().set(AuthRedisKeys.revokedSession(sid), "1", Duration.ofSeconds(30));

        assertThat(checker.isRevoked(claims())).isTrue();
    }

    @Test
    @DisplayName("회원 nbf 가 iat 보다 뒤면 거부한다 (제재 뒤에 남은 토큰)")
    void notBeforeAfterIssuedAtIsRevoked() {
        redis.opsForValue().set(AuthRedisKeys.notBefore(subject), String.valueOf(IAT.plusSeconds(1).getEpochSecond()), Duration.ofSeconds(30));

        assertThat(checker.isRevoked(claims())).isTrue();
    }

    @Test
    @DisplayName("회원 nbf 가 iat 와 같은 초여도 거부한다 — 초 정밀도라 경계를 안쪽으로 둔다")
    void notBeforeEqualToIssuedAtIsRevoked() {
        redis.opsForValue().set(AuthRedisKeys.notBefore(subject), String.valueOf(IAT.getEpochSecond()), Duration.ofSeconds(30));

        assertThat(checker.isRevoked(claims())).isTrue();
    }

    @Test
    @DisplayName("회원 nbf 가 iat 보다 앞이면 유효하다 (제재 뒤에 새로 로그인한 토큰)")
    void notBeforeBeforeIssuedAtIsValid() {
        redis.opsForValue().set(AuthRedisKeys.notBefore(subject), String.valueOf(IAT.minusSeconds(1).getEpochSecond()), Duration.ofSeconds(30));

        assertThat(checker.isRevoked(claims())).isFalse();
    }

    @Test
    @DisplayName("nbf 값이 숫자가 아니거나 Instant 범위 밖이면 거부한다 — 우리 키에 모르는 값은 정상이 아니다 (500 으로 새지 않는다)")
    void garbageNotBeforeIsRevoked() {
        for (String garbage : List.of("yesterday", "99999999999999999", "-99999999999999999", "9999999999999999999", "")) {
            redis.opsForValue().set(AuthRedisKeys.notBefore(subject), garbage, Duration.ofSeconds(30));

            assertThat(checker.isRevoked(claims())).as("nbf=%s", garbage).isTrue();
        }
    }

    @Test
    @DisplayName("실측: 응답을 안 주는 서버에는 commandTimeout(300ms) 안팎에서 조회 실패를 던진다 — 스레드가 잠기지 않는다")
    void hangingServerFailsWithinCommandTimeout() throws Exception {
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0)) {
            // accept 만 하고 아무 것도 안 보내는 서버. 연결은 되지만 명령 응답이 없다.
            Thread acceptor = new Thread(() -> {
                try {
                    while (!silent.isClosed()) {
                        silent.accept();
                    }
                } catch (java.io.IOException ignored) {
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            LettuceConnectionFactory hanging = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration("localhost", silent.getLocalPort()),
                    LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
            hanging.afterPropertiesSet();
            hanging.start();
            try {
                RevocationRedisChecker hangingChecker = new RevocationRedisChecker(new StringRedisTemplate(hanging));
                long started = System.nanoTime();

                assertThatThrownBy(() -> hangingChecker.isRevoked(claims()))
                        .isInstanceOf(RevocationCheckFailedException.class);

                long elapsedMs = (System.nanoTime() - started) / 1_000_000;
                assertThat(elapsedMs).isLessThan(3_000);   // 300ms 명령 타임아웃 + 핸드셰이크 여유. 무한 대기가 아님을 잰다
            } finally {
                hanging.destroy();
            }
        }
    }

    @Test
    @DisplayName("Redis 에 닿지 못하면 '폐기 아님' 이 아니라 조회 실패를 던진다 (판단은 필터의 경로별 정책)")
    void unreachableRedisFails() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6390),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
        dead.afterPropertiesSet();
        dead.start();
        try {
            RevocationRedisChecker deadChecker = new RevocationRedisChecker(new StringRedisTemplate(dead));

            assertThatThrownBy(() -> deadChecker.isRevoked(claims()))
                    .isInstanceOf(RevocationCheckFailedException.class);
        } finally {
            dead.destroy();
        }
    }
}
