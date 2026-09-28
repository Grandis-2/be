package com.grandis.nova.member.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.RedisConnectionFailureException;

@DisplayName("AdminCredentialRotationGuard — 자격증명 지문이 바뀌면 관리자 세션 전부 폐기")
class AdminCredentialRotationGuardTest {

    static final String HASH = "$2b$12$" + "a".repeat(53);
    static final AdminProperties PROPS = new AdminProperties("admin", HASH);

    AdminCredentialFingerprintStore store;
    TokenService tokens;
    AdminCredentialRotationGuard guard;

    @BeforeEach
    void setUp() {
        store = mock(AdminCredentialFingerprintStore.class);
        tokens = mock(TokenService.class);
        guard = new AdminCredentialRotationGuard(PROPS, store, tokens);
    }

    @Test
    @DisplayName("처음 기동(저장된 지문 없음): 폐기 없이 지문만 기록한다")
    void firstRunRecordsWithoutRevoking() {
        when(store.find()).thenReturn(Optional.empty());

        guard.onReady();

        verify(store).save(AdminCredentialRotationGuard.fingerprint(PROPS));
        verify(tokens, never()).revokeAll(any());
        assertThat(guard.isConfirmed()).isTrue();
    }

    @Test
    @DisplayName("지문이 다르면 관리자 세션 전부를 끊은 **뒤에** 새 지문을 기록한다 — 기록이 먼저면 폐기 실패가 다음 기동에서 묻힌다")
    void changedCredentialsRevokeThenRecord() {
        when(store.find()).thenReturn(Optional.of("stale"));

        guard.onReady();

        InOrder order = inOrder(tokens, store);
        order.verify(tokens).revokeAll(AdminLoginService.ADMIN_SUBJECT);
        order.verify(store).save(AdminCredentialRotationGuard.fingerprint(PROPS));
        assertThat(guard.isConfirmed()).isTrue();
    }

    @Test
    @DisplayName("지문이 같으면 아무것도 쓰지 않는다")
    void unchangedCredentialsDoNothing() {
        when(store.find()).thenReturn(Optional.of(AdminCredentialRotationGuard.fingerprint(PROPS)));

        guard.onReady();

        verify(tokens, never()).revokeAll(any());
        verify(store, never()).save(any());
        assertThat(guard.isConfirmed()).isTrue();
    }

    @Test
    @DisplayName("Redis 가 죽어 있으면 기동을 막지 않고 미확인으로 남긴다. 재시도에서 Redis 가 살아나면 그때 확인한다 — 폐기 실패 뒤 기록은 없다")
    void redisDownIsRetriedLater() {
        doThrow(new RedisConnectionFailureException("down")).when(store).find();

        guard.onReady();
        assertThat(guard.isConfirmed()).isFalse();
        verify(store, never()).save(any());

        // 폐기가 실패하면 지문을 기록하지 않는다 — 다음 재시도가 다시 폐기한다
        reset(store);
        when(store.find()).thenReturn(Optional.of("stale"));
        doThrow(new RedisConnectionFailureException("down")).when(tokens).revokeAll(any());
        guard.retry();
        assertThat(guard.isConfirmed()).isFalse();
        verify(store, never()).save(any());

        reset(tokens);
        guard.retry();
        assertThat(guard.isConfirmed()).isTrue();
        verify(tokens).revokeAll(AdminLoginService.ADMIN_SUBJECT);
        verify(store).save(AdminCredentialRotationGuard.fingerprint(PROPS));
    }

    @Test
    @DisplayName("로그인 전 확인: 저장된 지문이 이 인스턴스와 다르면(롤링 배포 중 옛 태스크) 503 retryable, 못 읽어도 503, 없거나 같으면 통과")
    void loginRefusedOnStaleInstance() {
        when(store.find()).thenReturn(Optional.of("fingerprint-of-new-credentials"));
        assertThatThrownBy(guard::requireCurrentForLogin)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));

        reset(store);
        doThrow(new RedisConnectionFailureException("down")).when(store).find();
        assertThatThrownBy(guard::requireCurrentForLogin).isInstanceOf(BusinessException.class);

        reset(store);
        when(store.find()).thenReturn(Optional.empty());
        guard.requireCurrentForLogin();
        when(store.find()).thenReturn(Optional.of(AdminCredentialRotationGuard.fingerprint(PROPS)));
        guard.requireCurrentForLogin();
        verify(tokens, never()).revokeAll(any());
    }

    @Test
    @DisplayName("지문은 username 과 해시 둘 다에 걸린다 — 하나만 바뀌어도 다르고, 해시 원문은 지문에 없다")
    void fingerprintCoversBothFields() {
        String base = AdminCredentialRotationGuard.fingerprint(PROPS);
        assertThat(base).hasSize(64).doesNotContain(HASH).doesNotContain("admin");
        assertThat(AdminCredentialRotationGuard.fingerprint(new AdminProperties("root", HASH))).isNotEqualTo(base);
        assertThat(AdminCredentialRotationGuard.fingerprint(new AdminProperties("admin", "$2b$12$" + "b".repeat(53)))).isNotEqualTo(base);
        assertThat(AdminCredentialRotationGuard.fingerprint(new AdminProperties("admin", HASH))).isEqualTo(base);
    }
}
