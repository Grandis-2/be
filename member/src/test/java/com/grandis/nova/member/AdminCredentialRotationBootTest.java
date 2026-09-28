package com.grandis.nova.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.common.security.AuthRedisKeys;
import com.grandis.nova.member.auth.application.AdminCredentialRotationGuard;
import com.grandis.nova.member.auth.application.AdminLoginService;
import com.grandis.nova.member.auth.application.AdminProperties;
import com.grandis.nova.member.support.Containers;
import com.grandis.nova.member.support.MemberIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.TestPropertySource;

/**
 * 가드의 배선 — 기동만으로 지문 대조 · 폐기 · 기록이 되고 재시도가 스케줄에 걸려 있는가. 실제 Redis 저장소로 뜨는 유일한 컨텍스트다.
 * 컨텍스트가 뜨기 전에 Redis 에 "옛 지문" 을 넣어 두고, 기동 뒤 폐기 표식과 새 지문이 있는지 본다.
 */
@MemberIntegrationTest
@TestPropertySource(properties = "auth.test.fingerprint-store=redis")
@DisplayName("AdminCredentialRotationGuard — 기동 배선 (실제 Redis)")
class AdminCredentialRotationBootTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @Autowired AdminCredentialRotationGuard guard;
    @Autowired AdminProperties adminProperties;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduledTasks;

    @BeforeAll
    static void plantStaleFingerprintBeforeBoot() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(Containers.redisHost(), Containers.redisPort()),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        redis.delete(AuthRedisKeys.notBefore(AdminLoginService.ADMIN_SUBJECT));
        redis.opsForValue().set(AuthRedisKeys.ADMIN_CREDENTIAL_FINGERPRINT, "fingerprint-of-previous-credentials");
    }

    @AfterAll
    static void cleanUp() {
        // 관리자 not-before 표식은 같은 초 발급까지 거부한다 — 다음 시험의 관리자 로그인에 번지지 않게
        redis.delete(AuthRedisKeys.notBefore(AdminLoginService.ADMIN_SUBJECT));
        redis.delete(AuthRedisKeys.ADMIN_CREDENTIAL_FINGERPRINT);
        factory.destroy();
    }

    @Test
    @DisplayName("기동만으로: 옛 지문이면 관리자 not-before 표식을 심고 새 지문을 기록한다. 확인됨 상태")
    void bootRevokesAndRecords() {
        assertThat(guard.isConfirmed()).isTrue();
        assertThat(redis.opsForValue().get(AuthRedisKeys.ADMIN_CREDENTIAL_FINGERPRINT))
                .isEqualTo(AdminCredentialRotationGuard.fingerprint(adminProperties));
        assertThat(redis.opsForValue().get(AuthRedisKeys.notBefore(AdminLoginService.ADMIN_SUBJECT)))
                .as("revokeAll(admin) 이 심는 표식").isNotNull();
    }

    @Test
    @DisplayName("재시도가 스케줄에 등록돼 있다 — @EnableScheduling 과 @Scheduled 가 함께 있어야 한다")
    void retryIsScheduled() {
        assertThat(scheduledTasks.getScheduledTasks())
                .anyMatch(task -> task.getTask().getRunnable().toString().contains("AdminCredentialRotationGuard.retry"));
    }
}
