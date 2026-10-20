package com.grandis.nova.member.auth.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.grandis.nova.member.support.Concurrently;
import com.grandis.nova.member.support.Containers;
import com.grandis.nova.common.security.AuthRedisKeys;
import com.grandis.nova.common.security.RevocationRedisChecker;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenClaims;
import com.grandis.nova.common.security.TokenType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 회전 성공 · jti 불일치 → 실패 · 키 없음 → 실패 · 동시 회전 2개 중 1개만 성공.
 * 그리고 쓰기(여기)와 읽기(common:security 의 RevocationRedisChecker)가 같은 키·같은 값 형식을 보는지 왕복으로 잰다.
 * 실제 Redis(컨테이너).
 */
@DisplayName("member Redis 저장소 (실제 Redis)")
class RedisStoresTest {

    private static final Instant NOW = Instant.parse("2026-09-19T15:00:00Z");
    private static final Duration ACCESS = Duration.ofHours(1);
    private static final Duration REFRESH = Duration.ofDays(14);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static RefreshTokenRedisStore refreshStore;
    private static RevocationRedisStore revocationStore;
    private static RevocationRedisChecker checker;

    private final UUID sid = UUID.randomUUID();
    private final String subject = "t-" + UUID.randomUUID();

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(Containers.redisHost(), Containers.redisPort()),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        refreshStore = new RefreshTokenRedisStore(redis);
        revocationStore = new RevocationRedisStore(redis, Clock.fixed(NOW, ZoneOffset.UTC));
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
        redis.delete(List.of(AuthRedisKeys.refresh(sid), AuthRedisKeys.refreshGrace(sid), AuthRedisKeys.revokedSession(sid),
                AuthRedisKeys.notBefore(subject)));
    }

    private TokenClaims claimsIssuedAt(Instant iat) {
        return new TokenClaims(subject, sid, UUID.randomUUID(), Role.USER, TokenType.ACCESS, iat, iat.plus(ACCESS));
    }

    @Nested
    @DisplayName("리프레시 회전")
    class Rotate {

        @Test
        @DisplayName("save 는 sid 키에 jti 를 TTL 과 함께 둔다")
        void saveStoresJtiWithTtl() {
            UUID jti = UUID.randomUUID();

            refreshStore.save(sid, jti, REFRESH);

            assertThat(redis.opsForValue().get(AuthRedisKeys.refresh(sid))).isEqualTo(jti.toString());
            assertThat(redis.getExpire(AuthRedisKeys.refresh(sid), TimeUnit.SECONDS)).isBetween(REFRESH.toSeconds() - 5, REFRESH.toSeconds());
        }

        @Test
        @DisplayName("저장된 jti 와 같으면 새 jti 로 바꾸고 true — 세션 키는 jti 하나 그대로(옛 버전도 읽는다), 유예 키에 바로 앞|다음|유예 끝(약 30초 뒤, TTL 30초)")
        void rotateSucceedsWhenExpectedMatches() {
            UUID current = UUID.randomUUID();
            UUID next = UUID.randomUUID();
            refreshStore.save(sid, current, REFRESH);

            assertThat(refreshStore.rotate(sid, current, next, Duration.ofDays(10))).isTrue();
            String[] value = stored();
            assertThat(value[0]).isEqualTo(next.toString());
            assertThat(value[1]).isEqualTo(current.toString());
            assertThat(value[2]).as("그 회전이 만든 다음 jti").isEqualTo(next.toString());
            assertThat(Long.parseLong(value[3]) - redisNowMillis()).isBetween(29_000L, 30_000L);
            assertThat(redis.opsForValue().get(AuthRedisKeys.refresh(sid))).as("옛 버전이 그대로 비교하는 모양").isEqualTo(next.toString());
            assertThat(redis.getExpire(AuthRedisKeys.refreshGrace(sid), TimeUnit.SECONDS)).isBetween(25L, 30L);
            assertThat(redis.getExpire(AuthRedisKeys.refresh(sid), TimeUnit.SECONDS)).isBetween(Duration.ofDays(10).toSeconds() - 5, Duration.ofDays(10).toSeconds());
        }

        @Test
        @DisplayName("저장된 jti 와 다르면(이미 회전된 리프레시 재사용) 바꾸지 않고 false")
        void rotateFailsWhenExpectedDiffers() {
            UUID current = UUID.randomUUID();
            refreshStore.save(sid, current, REFRESH);

            assertThat(refreshStore.rotate(sid, UUID.randomUUID(), UUID.randomUUID(), REFRESH)).isFalse();
            assertThat(redis.opsForValue().get(AuthRedisKeys.refresh(sid))).isEqualTo(current.toString());
        }

        @Test
        @DisplayName("유예: 교체 뒤 30초 안에 바로 앞 jti 가 다시 오면(다음 토큰 안 쓰임) 다시 회전한다 — 버려진 다음 jti 는 더 못 쓰고, 유예 끝은 그대로다")
        void rotatesAgainWithinGrace() {
            UUID first = UUID.randomUUID();
            UUID lost = UUID.randomUUID();
            UUID retried = UUID.randomUUID();
            refreshStore.save(sid, first, REFRESH);
            refreshStore.rotate(sid, first, lost, REFRESH);
            String graceUntil = stored()[3];

            assertThat(refreshStore.rotate(sid, first, retried, REFRESH)).isTrue();
            assertThat(stored()).as("다음 jti 는 새 것으로, 유예 끝은 그대로").containsExactly(retried.toString(), first.toString(), retried.toString(), graceUntil);
            assertThat(refreshStore.rotate(sid, lost, UUID.randomUUID(), REFRESH)).as("버려진 다음 jti").isFalse();
            assertThat(refreshStore.rotate(sid, retried, UUID.randomUUID(), REFRESH)).isTrue();
        }

        @Test
        @DisplayName("유예 안이어도 다음 토큰이 이미 쓰였으면(그 뒤로 회전) 바로 앞이 아니라 false — 받아 주는 건 바로 앞 jti 뿐이다")
        void graceOnlyForTheImmediatePredecessor() {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            refreshStore.save(sid, first, REFRESH);
            refreshStore.rotate(sid, first, second, REFRESH);
            refreshStore.rotate(sid, second, UUID.randomUUID(), REFRESH);

            assertThat(refreshStore.rotate(sid, first, UUID.randomUUID(), REFRESH)).isFalse();
        }

        @Test
        @DisplayName("섞인 배포: 유예 키를 모르는 옛 버전이 다음 토큰을 회전했으면(B→C, 유예 키는 A→B 그대로) 30초 안의 A 도 false — 지금 jti 가 기록된 다음 jti 와 다르다")
        void oldVersionRotationClosesTheGrace() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();
            refreshStore.save(sid, a, REFRESH);
            refreshStore.rotate(sid, a, b, REFRESH);
            redis.opsForValue().set(AuthRedisKeys.refresh(sid), c.toString(), REFRESH);   // 옛 버전의 회전: 세션 키만 바꾼다

            assertThat(refreshStore.rotate(sid, a, UUID.randomUUID(), REFRESH)).isFalse();
            assertThat(refreshStore.rotate(sid, c, UUID.randomUUID(), REFRESH)).as("정상 보유자는 그대로").isTrue();
        }

        @Test
        @DisplayName("유예 끝이 지났으면(Redis 시각 기준) 바로 앞 jti 도 false, 유예 끝 전이면 다시 회전한다 — 같은 밀리초의 경계는 시험으로 만들 수 없어 고정하지 않는다")
        void graceEnds() {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            refreshStore.save(sid, first, REFRESH);
            refreshStore.rotate(sid, first, second, REFRESH);
            redis.opsForValue().set(AuthRedisKeys.refreshGrace(sid), first + "|" + second + "|" + redisNowMillis(), REFRESH);

            assertThat(refreshStore.rotate(sid, first, UUID.randomUUID(), REFRESH)).isFalse();
            redis.opsForValue().set(AuthRedisKeys.refreshGrace(sid), first + "|" + second + "|" + (redisNowMillis() + 5_000), REFRESH);
            assertThat(refreshStore.rotate(sid, first, UUID.randomUUID(), REFRESH)).isTrue();
        }

        @Test
        @DisplayName("키가 없으면(로그아웃·만료) false 이고 키를 만들지 않는다")
        void rotateFailsWhenMissing() {
            assertThat(refreshStore.rotate(sid, UUID.randomUUID(), UUID.randomUUID(), REFRESH)).isFalse();
            assertThat(redis.hasKey(AuthRedisKeys.refresh(sid))).isFalse();
        }

        @Test
        @DisplayName("유예 값 모양이 깨졌으면(구분자 · 시각 누락) 스크립트 오류 없이 유예 없음 — 지금 jti 는 회전, 바로 앞 jti 는 false")
        void malformedValueMeansNoGrace() {
            UUID previous = UUID.randomUUID();
            for (String shape : new String[] {"%s", "%s|%s", "%s|%s|abc", "%s|%s|"}) {
                UUID current = UUID.randomUUID();
                refreshStore.save(sid, current, REFRESH);
                String broken = shape.formatted(previous, current);
                redis.opsForValue().set(AuthRedisKeys.refreshGrace(sid), broken, REFRESH);
                assertThat(refreshStore.rotate(sid, previous, UUID.randomUUID(), REFRESH)).as(broken).isFalse();
                assertThat(refreshStore.rotate(sid, current, UUID.randomUUID(), REFRESH)).as(broken).isTrue();
            }
        }

        @Test
        @DisplayName("같은 리프레시로 동시에 회전 8개(새로고침 연타) → Lua 가 하나씩 처리해 전부 회전하고, 남는 건 마지막 jti 하나 · 바로 앞은 처음 jti")
        void concurrentRotationsAllRotateOneSurvives() throws Exception {
            UUID current = UUID.randomUUID();
            refreshStore.save(sid, current, REFRESH);
            int n = 8;
            List<UUID> issued = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            List<Concurrently.Outcome<Boolean>> results = Concurrently.run(n, i -> () -> {
                UUID next = UUID.randomUUID();
                issued.add(next);
                return refreshStore.rotate(sid, current, next, REFRESH);
            });

            assertThat(results).allSatisfy(r -> assertThat(r.error()).isNull());
            assertThat(results).allMatch(Concurrently.Outcome::value);
            String[] value = stored();
            assertThat(issued).contains(UUID.fromString(value[0]));
            assertThat(value[1]).isEqualTo(current.toString());
            assertThat(issued.stream().filter(jti -> !jti.toString().equals(value[0])))
                    .allSatisfy(jti -> assertThat(refreshStore.rotate(sid, jti, UUID.randomUUID(), REFRESH)).as("앞 회전들이 만든 jti").isFalse());
        }

        @Test
        @DisplayName("delete 뒤에는 회전이 실패한다")
        void deleteRemovesKey() {
            UUID first = UUID.randomUUID();
            UUID current = UUID.randomUUID();
            refreshStore.save(sid, first, REFRESH);
            refreshStore.rotate(sid, first, current, REFRESH);
            assertThat(redis.hasKey(AuthRedisKeys.refreshGrace(sid))).as("회전이 유예 키를 만들었다").isTrue();

            refreshStore.delete(sid);
            assertThat(redis.hasKey(AuthRedisKeys.refreshGrace(sid))).as("유예 키도 지운다").isFalse();

            assertThat(refreshStore.rotate(sid, current, UUID.randomUUID(), REFRESH)).isFalse();
            assertThat(refreshStore.rotate(sid, first, UUID.randomUUID(), REFRESH)).as("바로 앞 jti 도 유예가 없다").isFalse();
        }
    }

    /** [지금 jti, 바로 앞 jti, 다음 jti, 유예 끝] — 세션 키와 유예 키를 합쳐 읽는다. 유예 키가 없으면 [지금 jti]. */
    private String[] stored() {
        String current = redis.opsForValue().get(AuthRedisKeys.refresh(sid));
        String grace = redis.opsForValue().get(AuthRedisKeys.refreshGrace(sid));
        return grace == null ? new String[] {current} : (current + "|" + grace).split("\\|");
    }

    /** 유예 판정과 같은 시계(Redis TIME) — 앱 시계와 어긋나도 경계를 같은 쪽에서 잰다. */
    private long redisNowMillis() {
        return redis.execute((org.springframework.data.redis.core.RedisCallback<Long>) connection -> connection.serverCommands().time());
    }

    @Nested
    @DisplayName("폐기 표식 쓰기 ↔ 체커 읽기")
    class Revocation {

        @Test
        @DisplayName("revokeSession 뒤에 체커가 그 sid 를 거부한다. TTL 은 액세스 만료")
        void revokeSessionIsSeenByChecker() {
            assertThat(checker.isRevoked(claimsIssuedAt(NOW))).isFalse();

            revocationStore.revokeSession(sid, ACCESS);

            assertThat(checker.isRevoked(claimsIssuedAt(NOW))).isTrue();
            assertThat(redis.getExpire(AuthRedisKeys.revokedSession(sid), TimeUnit.SECONDS)).isBetween(ACCESS.toSeconds() - 5, ACCESS.toSeconds());
        }

        @Test
        @DisplayName("replaceSession 은 이유(replaced)와 리프레시 만료 TTL 로 심고, 그 뒤 같은 sid 의 revokeSession(밀린 기기의 로그아웃)이 덮지 않는다")
        void replacedMarkSurvivesLaterRevoke() {
            revocationStore.replaceSession(sid, REFRESH);
            revocationStore.revokeSession(sid, ACCESS);

            assertThat(checker.isRevoked(claimsIssuedAt(NOW))).isTrue();
            assertThat(checker.isReplaced(claimsIssuedAt(NOW))).isTrue();
            assertThat(redis.getExpire(AuthRedisKeys.revokedSession(sid), TimeUnit.SECONDS)).isBetween(REFRESH.toSeconds() - 5, REFRESH.toSeconds());
        }

        @Test
        @DisplayName("revokeAll 은 지금(초) 을 nbf 로 심는다. 그 전·같은 초 발급은 거부, 다음 초 발급은 통과")
        void revokeAllUsesNotBeforeBoundary() {
            revocationStore.revokeAll(subject, REFRESH);

            assertThat(redis.opsForValue().get(AuthRedisKeys.notBefore(subject))).isEqualTo(String.valueOf(NOW.getEpochSecond()));
            assertThat(checker.isRevoked(claimsIssuedAt(NOW.minusSeconds(60)))).as("제재 전 발급").isTrue();
            assertThat(checker.isRevoked(claimsIssuedAt(NOW))).as("제재와 같은 초 발급").isTrue();
            assertThat(checker.isRevoked(claimsIssuedAt(NOW.plusSeconds(1)))).as("제재 다음 초 발급 = 재로그인").isFalse();
            assertThat(redis.getExpire(AuthRedisKeys.notBefore(subject), TimeUnit.SECONDS)).isBetween(REFRESH.toSeconds() - 5, REFRESH.toSeconds());
        }
    }

    @Test
    @DisplayName("실측: 닫힌 포트의 Redis 에 쓰면 두 저장소 모두 DataAccessException 하위로 온다 — 로그아웃이 삼키는 타입의 근거")
    void deadRedisThrowsDataAccessException() throws Exception {
        int deadPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        LettuceConnectionFactory dead = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", deadPort),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
        dead.afterPropertiesSet();
        dead.start();
        try {
            StringRedisTemplate deadRedis = new StringRedisTemplate(dead);
            Throwable fromDelete = catchThrowable(() -> new RefreshTokenRedisStore(deadRedis).delete(sid));
            Throwable fromRevoke = catchThrowable(() -> new RevocationRedisStore(deadRedis, Clock.fixed(NOW, ZoneOffset.UTC)).revokeSession(sid, ACCESS));
            assertThat(fromDelete).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(fromRevoke).isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            dead.destroy();
        }
    }
}
