package com.grandis.nova.member.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grandis.nova.common.security.InvalidTokenException;
import com.grandis.nova.common.security.JwtProperties;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationCheckFailedException;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.RevocationLookupUnavailableException;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenClaims;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.member.auth.application.RefreshTokenStore.Rotation;
import com.grandis.nova.member.auth.application.RefreshTokenStore.Session;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * 회원 리프레시는 DB 정본(저장소 모킹), 관리자 리프레시는 Redis. 토큰은 진짜로 만든다.
 *
 * 저장소의 판정(없음·폐기·만료·재사용)은 여기서 모킹한 값이다 — 그 판정이 실제 DB 에서 맞는지는
 * RefreshTokenDbStoreTest 가 실제 MySQL 로 본다. 여기서 보는 것은 "판정마다 서비스가 무엇을 하는가" 다.
 */
@DisplayName("TokenService")
class TokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T16:00:00Z");
    private static final Duration ACCESS = Duration.ofHours(1);
    private static final Duration REFRESH = Duration.ofDays(14);
    private static final ClientInfo CLIENT = new ClientInfo("203.0.113.9", "JUnit");
    private static final String PRESENTED = "presented-opaque-refresh";

    private final MutableClock clock = new MutableClock(NOW);
    private final JwtProperties properties = com.grandis.nova.member.TestKeys.issuerProperties("nova-test", ACCESS, REFRESH);
    private final JwtTokenProvider provider = com.grandis.nova.member.TestKeys.issuer(properties, clock);
    private RefreshTokenStore refreshTokens;
    private AdminRefreshTokenStore adminRefreshTokens;
    private RevocationStore revocations;
    private RevocationChecker checker;
    private TokenService service;

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void set(Instant i) { now = i; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        refreshTokens = mock(RefreshTokenStore.class);
        adminRefreshTokens = mock(AdminRefreshTokenStore.class);
        revocations = mock(RevocationStore.class);
        checker = mock(RevocationChecker.class);
        when(checker.isRevoked(any())).thenReturn(false);
        service = new TokenService(provider, properties, refreshTokens, adminRefreshTokens, revocations, checker, clock);
    }

    /** 로그인해 둔 회원 세션 하나. 제시할 원문은 PRESENTED 로 고정한다(저장소가 모킹이라 값 자체는 불투명하면 된다). */
    private Session loggedIn(UUID sessionId) {
        Session session = new Session(sessionId, "101", NOW, NOW.plus(REFRESH));
        when(refreshTokens.find(PRESENTED)).thenReturn(Optional.of(session));
        return session;
    }

    private void rotationSucceeds(Session session) {
        when(refreshTokens.rotate(eq(PRESENTED), any(), any()))
                .thenReturn(new Rotation(Rotation.Status.ROTATED, session.sessionId(), session.subject(), session.expiresAt()));
    }

    // ────────────────────────────── 발급

    @Test
    @DisplayName("issue(회원): 액세스는 JWT, 리프레시는 불투명 난수(JWT 가 아니다). 같은 sid 로 DB 에 14일 만료로 저장된다")
    void issueStoresOpaqueRefreshInDatabase() {
        TokenService.IssuedTokens tokens = service.issue("101", Role.USER, CLIENT);

        TokenClaims access = provider.parse(tokens.accessToken());
        assertThat(access.type()).isEqualTo(TokenType.ACCESS);
        assertThatThrownBy(() -> provider.parse(tokens.refreshToken()))
                .as("리프레시는 JWT 가 아니라 난수라 파싱되지 않는다").isInstanceOf(InvalidTokenException.class);
        assertThat(tokens.refreshToken()).hasSize(43);   // 256비트 base64url, 패딩 없음
        assertThat(tokens.refreshTokenMaxAge()).isEqualTo(REFRESH);
        verify(refreshTokens).save(access.sessionId(), "101", tokens.refreshToken(), NOW.plus(REFRESH), CLIENT);
    }

    @Test
    @DisplayName("issue: 두 번 발급하면 원문이 매번 다르다")
    void issuedRefreshTokensAreUnique() {
        assertThat(service.issue("101", Role.USER, CLIENT).refreshToken())
                .isNotEqualTo(service.issue("101", Role.USER, CLIENT).refreshToken());
    }

    @Test
    @DisplayName("issue(관리자): 리프레시는 JWT 이고 Redis 저장소가 jti 를 든다 — 회원 표 외래키 때문에 DB 행을 만들 수 없다")
    void adminIssueUsesRedisStore() {
        TokenService.IssuedTokens tokens = service.issue("admin", Role.ADMIN, CLIENT);

        TokenClaims refresh = provider.parse(tokens.refreshToken());
        assertThat(refresh.type()).isEqualTo(TokenType.REFRESH);
        verify(adminRefreshTokens).save(refresh.sessionId(), refresh.tokenId(), REFRESH);
        verify(refreshTokens, never()).save(any(), any(), any(), any(), any());
    }

    // ────────────────────────────── 회전

    @Test
    @DisplayName("rotate: 3일 뒤 회전해도 절대 만료는 그대로고 쿠키 Max-Age 는 남은 11일. 새 원문은 옛 원문과 다르다")
    void rotateKeepsAbsoluteExpiry() {
        Session session = loggedIn(UUID.randomUUID());
        rotationSucceeds(session);
        clock.set(NOW.plus(Duration.ofDays(3)));

        TokenService.Rotated rotated = service.rotate(PRESENTED, Role.USER, CLIENT);

        assertThat(rotated.tokens().refreshToken()).isNotEqualTo(PRESENTED).hasSize(43);
        assertThat(rotated.tokens().refreshTokenMaxAge()).isEqualTo(Duration.ofDays(11));
        assertThat(rotated.subject()).isEqualTo("101");
        assertThat(rotated.sessionId()).isEqualTo(session.sessionId());
        TokenClaims newAccess = provider.parse(rotated.tokens().accessToken());
        assertThat(newAccess.sessionId()).isEqualTo(session.sessionId());
        assertThat(newAccess.issuedAt()).isEqualTo(NOW.plus(Duration.ofDays(3)));
        ArgumentCaptor<String> newToken = ArgumentCaptor.forClass(String.class);
        verify(refreshTokens).rotate(eq(PRESENTED), newToken.capture(), eq(CLIENT));
        assertThat(newToken.getValue()).isEqualTo(rotated.tokens().refreshToken());
    }

    @Test
    @DisplayName("rotate: 저장소가 재사용으로 판정하면 sid 표식 먼저 → DB 체인 폐기 → 관리자 키 삭제, 그리고 401")
    void reuseRevokesSession() {
        Session session = loggedIn(UUID.randomUUID());
        when(refreshTokens.rotate(any(), any(), any()))
                .thenReturn(Rotation.rejected(Rotation.Status.REUSED, session.sessionId(), session.subject()));

        assertThatThrownBy(() -> service.rotate(PRESENTED, Role.USER, CLIENT)).isInstanceOf(InvalidTokenException.class);

        InOrder order = Mockito.inOrder(revocations, refreshTokens, adminRefreshTokens);
        order.verify(revocations).revokeSession(session.sessionId(), ACCESS);   // 필터가 읽는 표식이 먼저
        order.verify(refreshTokens).revokeSession(session.sessionId());
        order.verify(adminRefreshTokens).delete(session.sessionId());
    }

    @Test
    @DisplayName("rotate 재사용 분기: 표식 쓰기가 실패해도 체인 폐기를 시도하고, 응답은 그대로 401 (저장소 예외를 500 으로 안 올림)")
    void reusePartialFailureStillReturns401() {
        Session session = loggedIn(UUID.randomUUID());
        when(refreshTokens.rotate(any(), any(), any()))
                .thenReturn(Rotation.rejected(Rotation.Status.REUSED, session.sessionId(), session.subject()));
        doThrow(new RedisConnectionFailureException("down")).when(revocations).revokeSession(any(), any());

        assertThatThrownBy(() -> service.rotate(PRESENTED, Role.USER, CLIENT)).isInstanceOf(InvalidTokenException.class);
        verify(refreshTokens).revokeSession(session.sessionId());
    }

    @Test
    @DisplayName("rotate: 저장소가 없음·폐기됨·만료로 판정하면 401 이고 세션을 더 끊지 않는다(이미 못 쓰는 토큰이다)")
    void rejectedRotationsAre401() {
        for (Rotation.Status status : new Rotation.Status[] {
                Rotation.Status.NOT_FOUND, Rotation.Status.REVOKED, Rotation.Status.EXPIRED}) {
            Mockito.reset(refreshTokens, revocations);
            when(checker.isRevoked(any())).thenReturn(false);
            Session session = loggedIn(UUID.randomUUID());
            when(refreshTokens.rotate(any(), any(), any())).thenReturn(Rotation.rejected(status, session.sessionId(), session.subject()));

            assertThatThrownBy(() -> service.rotate(PRESENTED, Role.USER, CLIENT))
                    .as("%s", status).isInstanceOf(InvalidTokenException.class);
            verify(revocations, never()).revokeSession(any(), any());
        }
    }

    @Test
    @DisplayName("rotate: 저장소에 없는 원문이면 회전을 시도조차 하지 않고 401")
    void unknownRefreshTokenIsRejectedBeforeRotation() {
        when(refreshTokens.find(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("nonexistent", Role.USER, CLIENT)).isInstanceOf(InvalidTokenException.class);
        verify(refreshTokens, never()).rotate(any(), any(), any());
    }

    @Test
    @DisplayName("rotate: 제재(nbf)·로그아웃된 세션의 리프레시는 401 — 재발급이 제재 우회 통로가 되지 않는다")
    void rotateRejectsRevokedSession() {
        loggedIn(UUID.randomUUID());
        when(checker.isRevoked(any())).thenReturn(true);

        assertThatThrownBy(() -> service.rotate(PRESENTED, Role.USER, CLIENT)).isInstanceOf(InvalidTokenException.class);
        verify(refreshTokens, never()).rotate(any(), any(), any());
    }

    @Test
    @DisplayName("rotate: 폐기 조회가 안 되면 닫는다(재발급은 조회 실패 시 닫는 경로) — retryable 401, 회전 안 함")
    void rotateClosesWhenLookupUnavailable() {
        loggedIn(UUID.randomUUID());
        when(checker.isRevoked(any())).thenThrow(new RevocationCheckFailedException(new RuntimeException("down")));

        assertThatThrownBy(() -> service.rotate(PRESENTED, Role.USER, CLIENT)).isInstanceOf(RevocationLookupUnavailableException.class);
        verify(refreshTokens, never()).rotate(any(), any(), any());
    }

    @Test
    @DisplayName("rotate: 첫 검사와 발급 사이에 전체 폐기가 끼면(두 번째 검사가 true) 세션을 끊고 401 — 새 토큰이 표식을 빠져나가지 못한다")
    void revokedBetweenCheckAndIssueIsCaughtBySecondCheck() {
        Session session = loggedIn(UUID.randomUUID());
        rotationSucceeds(session);
        when(checker.isRevoked(any())).thenReturn(false, true);   // 회전 전 false, 회전 후 true

        assertThatThrownBy(() -> service.rotate(PRESENTED, Role.USER, CLIENT)).isInstanceOf(InvalidTokenException.class);

        verify(checker, Mockito.times(2)).isRevoked(any());
        verify(revocations).revokeSession(session.sessionId(), ACCESS);
        verify(refreshTokens).revokeSession(session.sessionId());
    }

    @Test
    @DisplayName("rotate: 두 번째 검사가 조회 불가면 폐기로 본다 — 세션을 끊고 401")
    void secondCheckLookupFailureRevokes() {
        Session session = loggedIn(UUID.randomUUID());
        rotationSucceeds(session);
        when(checker.isRevoked(any())).thenReturn(false).thenThrow(new RevocationCheckFailedException(new RuntimeException("down")));

        assertThatThrownBy(() -> service.rotate(PRESENTED, Role.USER, CLIENT)).isInstanceOf(InvalidTokenException.class);
        verify(revocations).revokeSession(session.sessionId(), ACCESS);
    }

    @Test
    @DisplayName("rotate: 폐기 표식 조회에 넘기는 발급 시각은 저장소의 생성 시각이다 — 같은 초에 심은 제재가 이 토큰을 잡는다")
    void revocationCheckUsesStoredIssuedAt() {
        Session session = loggedIn(UUID.randomUUID());
        rotationSucceeds(session);

        service.rotate(PRESENTED, Role.USER, CLIENT);

        ArgumentCaptor<TokenClaims> claims = ArgumentCaptor.forClass(TokenClaims.class);
        verify(checker, Mockito.atLeastOnce()).isRevoked(claims.capture());
        assertThat(claims.getValue().issuedAt()).isEqualTo(session.issuedAt());
        assertThat(claims.getValue().sessionId()).isEqualTo(session.sessionId());
        assertThat(claims.getValue().subject()).isEqualTo("101");
        // 지금 체커는 위 셋만 읽는다. 나머지 둘은 아무도 안 보지만 거짓이면 안 된다 —
        // 체커에 역할이나 종류를 보는 규칙이 하나 생기는 순간 폐기 판정이 조용히 빗나간다.
        assertThat(claims.getValue().role()).isEqualTo(Role.USER);
        assertThat(claims.getValue().type()).isEqualTo(TokenType.REFRESH);
    }

    @Test
    @DisplayName("저장하는 시각은 초로 자른다 — 폐기 표식이 epoch 초라 정밀도가 다르면 경계가 양쪽으로 기운다")
    void storedTimesAreTruncatedToSeconds() {
        clock.set(NOW.plusNanos(123_456_789));

        TokenService.IssuedTokens tokens = service.issue("101", Role.USER, CLIENT);

        verify(refreshTokens).save(any(), eq("101"), eq(tokens.refreshToken()), eq(NOW.plus(REFRESH)), eq(CLIENT));
    }

    @Test
    @DisplayName("rotate(관리자): Redis 비교교환이 성공해야 회전한다. 실패(재사용)면 세션을 끊고 401")
    void adminRotateUsesRedisCompareAndSwap() {
        TokenService.IssuedTokens first = service.issue("admin", Role.ADMIN, CLIENT);
        TokenClaims refresh = provider.parse(first.refreshToken());
        when(adminRefreshTokens.rotate(eq(refresh.sessionId()), eq(refresh.tokenId()), any(), any())).thenReturn(true);

        TokenService.Rotated rotated = service.rotate(first.refreshToken(), Role.ADMIN, CLIENT);
        assertThat(provider.parse(rotated.tokens().refreshToken()).expiresAt()).isEqualTo(refresh.expiresAt());

        when(adminRefreshTokens.rotate(any(), any(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.rotate(first.refreshToken(), Role.ADMIN, CLIENT)).isInstanceOf(InvalidTokenException.class);
        verify(revocations).revokeSession(refresh.sessionId(), ACCESS);
    }

    @Test
    @DisplayName("rotate(관리자): 회원 역할의 리프레시 JWT 를 관리자 쿠키로 내면 401 — 쿠키 이름만으로 관리자로 취급하지 않는다")
    void adminRotateRejectsUserRoleToken() {
        String userRefreshJwt = provider.create("101", Role.USER, UUID.randomUUID(), TokenType.REFRESH);

        assertThatThrownBy(() -> service.rotate(userRefreshJwt, Role.ADMIN, CLIENT)).isInstanceOf(InvalidTokenException.class);
        verify(adminRefreshTokens, never()).rotate(any(), any(), any(), any());
    }

    @Test
    @DisplayName("rotate(관리자): 액세스 토큰을 내면 401 이고 저장소를 건드리지 않는다")
    void adminRotateRejectsAccessToken() {
        TokenService.IssuedTokens first = service.issue("admin", Role.ADMIN, CLIENT);

        assertThatThrownBy(() -> service.rotate(first.accessToken(), Role.ADMIN, CLIENT)).isInstanceOf(InvalidTokenException.class);
        verify(adminRefreshTokens, never()).rotate(any(), any(), any(), any());
    }

    // ────────────────────────────── 조회·폐기

    @Test
    @DisplayName("subjectOf: 불투명 리프레시의 주인은 저장소가 알려 준다. 없는 원문이면 401")
    void subjectOfReadsTheStore() {
        loggedIn(UUID.randomUUID());
        assertThat(service.subjectOf(PRESENTED, Role.USER)).isEqualTo("101");

        when(refreshTokens.find("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.subjectOf("gone", Role.USER)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("sessionOfUserRefresh: 로그아웃이 쿠키만 들고 왔을 때 그 쿠키의 세션을 찾는다")
    void sessionOfUserRefreshLooksUpTheStore() {
        Session session = loggedIn(UUID.randomUUID());
        assertThat(service.sessionOfUserRefresh(PRESENTED)).contains(session.sessionId());
        assertThat(service.sessionOfUserRefresh("gone")).isEmpty();
    }

    @Test
    @DisplayName("revoke(로그아웃): sid 표식(TTL=액세스 만료) → DB 체인 폐기 → 관리자 키 삭제 순")
    void revokeMarksThenRevokesChain() {
        UUID sessionId = UUID.randomUUID();

        service.revoke(sessionId);

        InOrder order = Mockito.inOrder(revocations, refreshTokens, adminRefreshTokens);
        order.verify(revocations).revokeSession(sessionId, ACCESS);
        order.verify(refreshTokens).revokeSession(sessionId);
        order.verify(adminRefreshTokens).delete(sessionId);
    }

    @Test
    @DisplayName("revoke: 하나가 실패해도 나머지는 시도하고, 저장소 예외는 그대로 올라간다(호출자가 503 으로 낸다)")
    void revokeTriesEveryStoreAndPropagates() {
        UUID sessionId = UUID.randomUUID();
        doThrow(new RedisConnectionFailureException("down")).when(revocations).revokeSession(any(), any());

        assertThatThrownBy(() -> service.revoke(sessionId)).isInstanceOf(DataAccessException.class);
        verify(refreshTokens).revokeSession(sessionId);
        verify(adminRefreshTokens).delete(sessionId);

        Mockito.reset(refreshTokens, revocations, adminRefreshTokens);
        doThrow(new RedisConnectionFailureException("down")).when(refreshTokens).revokeSession(any());
        assertThatThrownBy(() -> service.revoke(sessionId)).isInstanceOf(DataAccessException.class);
        verify(revocations).revokeSession(sessionId, ACCESS);
        verify(adminRefreshTokens).delete(sessionId);
    }

    @Test
    @DisplayName("revoke: 저장소 예외가 아닌 것은 삼키지 않고 즉시 전파한다 (프로그래밍 오류가 204 뒤에 숨지 않게)")
    void revokePropagatesNonDataAccessErrors() {
        UUID sessionId = UUID.randomUUID();
        doThrow(new IllegalStateException("bug")).when(revocations).revokeSession(any(), any());

        assertThatThrownBy(() -> service.revoke(sessionId)).isInstanceOf(IllegalStateException.class);
        verify(refreshTokens, never()).revokeSession(any());
    }

    @Test
    @DisplayName("revokeAll(제재): 회원 nbf 표식을 먼저 심고(액세스 차단) DB 의 살아 있는 리프레시를 전부 폐기한다")
    void revokeAllMarksThenRevokesRows() {
        service.revokeAll("101");

        InOrder order = Mockito.inOrder(revocations, refreshTokens);
        order.verify(revocations).revokeAll("101", REFRESH);
        order.verify(refreshTokens).revokeAllOf("101");
    }
}
