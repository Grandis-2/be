package com.grandis.nova.member.auth.application;

import com.grandis.nova.common.security.InvalidTokenException;
import com.grandis.nova.common.security.JwtProperties;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationCheckFailedException;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.RevocationLookupUnavailableException;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenClaims;
import com.grandis.nova.common.security.TokenType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 발급·회전·폐기·전체 폐기 넷.
 *
 * <h2>무엇이 어디에 있나</h2>
 * 회원 리프레시의 **정본은 DB**(`shop.refresh_tokens`)다. 원문은 256비트 난수이고 저장소에는 SHA-256 만 남는다.
 * Redis 는 보조다 — 액세스 토큰을 즉시 끊는 폐기 표식(세션 sid · 회원 not-before)과, 관리자 세션의 리프레시를 든다.
 * 관리자가 DB 를 쓰지 않는 이유는 `refresh_tokens.customer_id` 가 `customers` 를 가리키는 NOT NULL 외래키이고
 * 관리자 계정은 회원 표 밖에 있기 때문이다.
 *
 * <p>리프레시의 사본을 Redis 에 두지 않는다. 사본이 정본보다 오래된 값을 들고 있으면 정상 회전을 재사용으로 오판해
 * 멀쩡한 세션을 끊는다 — 얻는 것(행 잠금 한 번)보다 잃는 것이 크다. 판정은 항상 DB 가 한다.
 *
 * <h2>회전(RTR)이 지나는 검사</h2>
 * <ol>
 *   <li>그 원문의 행이 있는가(없으면 401).</li>
 *   <li>폐기 표식이 없는가 — 재발급은 공개 경로라 필터의 폐기 조회를 안 거친다. 여기서 직접 본다.
 *       안 보면 제재된 회원이 리프레시로 새 토큰을 받아 제재를 우회한다. 조회가 안 되면 닫는다 → retryable 401.</li>
 *   <li>DB 가 행을 잠그고 판정한다(폐기·만료·이미 교체됨). 이미 교체된 원문이면 그 체인을 통째로 끊고 401.</li>
 *   <li>회전 뒤 폐기 표식을 한 번 더 본다 — 2와 발급 사이에 전체 폐기가 끼면 새 토큰이 그 표식을 빠져나가기 때문이다.</li>
 * </ol>
 * 회전해도 절대 만료는 늘지 않는다. 새 행이 원 행의 만료 시각을 물려받는다.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    /**
     * 폐기 표식 조회에 넘기는 클레임의 jti 자리. 표식 조회는 sid·subject·발급 시각만 읽고 jti 는 안 본다
     * (RevocationRedisChecker). 불투명 리프레시에는 jti 가 없으므로 고정값을 넣는다.
     */
    private static final UUID NO_TOKEN_ID = new UUID(0L, 0L);

    private final JwtTokenProvider provider;
    private final JwtProperties properties;
    private final RefreshTokenStore refreshTokens;
    private final AdminRefreshTokenStore adminRefreshTokens;
    private final RevocationStore revocations;
    private final RevocationChecker revocationChecker;
    private final Clock clock;

    public TokenService(JwtTokenProvider provider, JwtProperties properties, RefreshTokenStore refreshTokens,
                        AdminRefreshTokenStore adminRefreshTokens, RevocationStore revocations,
                        RevocationChecker revocationChecker, Clock clock) {
        this.provider = provider;
        this.properties = properties;
        this.refreshTokens = refreshTokens;
        this.adminRefreshTokens = adminRefreshTokens;
        this.revocations = revocations;
        this.revocationChecker = revocationChecker;
        this.clock = clock;
    }

    /** 로그인. 새 sid 하나에 액세스·리프레시 한 쌍. 회원은 DB 에 행을, 관리자는 Redis 에 jti 를 남긴다. */
    public IssuedTokens issue(String subject, Role role, ClientInfo client) {
        UUID sessionId = UUID.randomUUID();
        String access = provider.create(subject, role, sessionId, TokenType.ACCESS);
        Duration validity = properties.refreshTokenValidity();
        if (role == Role.ADMIN) {
            String refresh = provider.create(subject, role, sessionId, TokenType.REFRESH);
            adminRefreshTokens.save(sessionId, provider.parse(refresh).tokenId(), validity);
            return new IssuedTokens(access, refresh, validity);
        }
        String refresh = RefreshTokens.newToken();
        refreshTokens.save(sessionId, subject, refresh, now().plus(validity), client);
        return new IssuedTokens(access, refresh, validity);
    }

    /** 재발급 전에 회원 이름을 읽으려면 주인을 먼저 알아야 한다. 판정은 하지 않는다 — 여기서 통과해도 회전은 따로 검사한다. */
    public String subjectOf(String rawRefreshToken, Role role) {
        if (role == Role.ADMIN) {
            return provider.parse(rawRefreshToken).subject();
        }
        return refreshTokens.find(rawRefreshToken)
                .orElseThrow(() -> new InvalidTokenException("refresh token not found"))
                .subject();
    }

    /** 로그아웃이 회원 리프레시 쿠키만 들고 왔을 때 그 쿠키가 가리키는 세션. 폐기·만료된 것도 찾는다. */
    public Optional<UUID> sessionOfUserRefresh(String rawRefreshToken) {
        return refreshTokens.find(rawRefreshToken).map(RefreshTokenStore.Session::sessionId);
    }

    /** 재발급. 실패는 전부 401 이다. 재사용 탐지면 세션을 끊고 401, 폐기 조회 불가면 retryable 401. */
    public Rotated rotate(String presentedRefreshToken, Role role, ClientInfo client) {
        return role == Role.ADMIN
                ? rotateAdmin(presentedRefreshToken)
                : rotateUser(presentedRefreshToken, client);
    }

    private Rotated rotateUser(String presented, ClientInfo client) {
        RefreshTokenStore.Session session = refreshTokens.find(presented)
                .orElseThrow(() -> new InvalidTokenException("refresh token not found"));
        TokenClaims asClaims = claimsOf(session);
        if (revokedBeforeRotation(asClaims)) {
            throw new InvalidTokenException("refresh of revoked session sid=" + shortSid(session.sessionId()));
        }

        String newRefresh = RefreshTokens.newToken();
        RefreshTokenStore.Rotation rotation = refreshTokens.rotate(presented, newRefresh, client);
        if (rotation.status() == RefreshTokenStore.Rotation.Status.REUSED) {
            // 저장소가 체인을 이미 폐기하고 커밋했다. 남은 것은 액세스 토큰이고 그건 Redis 표식이 끊는다.
            revokeSessionBestEffort(rotation.sessionId(), "refresh reuse");
            log.warn("refresh reuse detected, session revoked sid={}", shortSid(rotation.sessionId()));
            throw new InvalidTokenException("refresh reuse sid=" + shortSid(rotation.sessionId()));
        }
        if (rotation.status() != RefreshTokenStore.Rotation.Status.ROTATED) {
            throw new InvalidTokenException("refresh rejected: " + rotation.status());
        }

        if (revokedDuringRotation(asClaims)) {
            revokeSessionBestEffort(session.sessionId(), "revoked during rotation");
            throw new InvalidTokenException("session revoked during rotation sid=" + shortSid(session.sessionId()));
        }
        String access = provider.create(session.subject(), Role.USER, session.sessionId(), TokenType.ACCESS);
        Duration remaining = Duration.between(clock.instant(), rotation.expiresAt());
        return new Rotated(new IssuedTokens(access, newRefresh, remaining), session.subject(), session.sessionId());
    }

    /** 관리자 세션. 리프레시는 JWT 이고 현재 jti 하나를 Redis 가 비교교환으로 든다. */
    private Rotated rotateAdmin(String presented) {
        TokenClaims claims = provider.parse(presented);
        if (claims.type() != TokenType.REFRESH) {
            throw new InvalidTokenException("not a refresh token: " + claims.type());
        }
        // 관리자 쿠키로 왔다고 관리자 토큰인 것은 아니다. 역할을 안 보면 예전 방식으로 발급된 회원 리프레시 JWT 가
        // 관리자 쿠키에 담겨 이 경로를 지나고, 응답과 쿠키만 관리자로 처리되어 토큰의 실제 역할과 어긋난다.
        if (claims.role() != Role.ADMIN) {
            throw new InvalidTokenException("not an admin refresh token: " + claims.role());
        }
        if (revokedBeforeRotation(claims)) {
            throw new InvalidTokenException("refresh of revoked session sid=" + shortSid(claims.sessionId()));
        }
        // parse 가 먼저 만료(exp ≤ now)를 떨구므로 여기 도달하는 remaining 은 항상 양수다.
        Duration remaining = Duration.between(clock.instant(), claims.expiresAt());
        String newRefresh = provider.create(claims.subject(), claims.role(), claims.sessionId(), TokenType.REFRESH, claims.expiresAt());
        UUID newTokenId = provider.parse(newRefresh).tokenId();
        if (!adminRefreshTokens.rotate(claims.sessionId(), claims.tokenId(), newTokenId, remaining)) {
            revokeSessionBestEffort(claims.sessionId(), "refresh reuse");
            log.warn("refresh reuse detected, session revoked sid={}", shortSid(claims.sessionId()));
            throw new InvalidTokenException("refresh reuse sid=" + shortSid(claims.sessionId()));
        }
        if (revokedDuringRotation(claims)) {
            revokeSessionBestEffort(claims.sessionId(), "revoked during rotation");
            throw new InvalidTokenException("session revoked during rotation sid=" + shortSid(claims.sessionId()));
        }
        String access = provider.create(claims.subject(), claims.role(), claims.sessionId(), TokenType.ACCESS);
        return new Rotated(new IssuedTokens(access, newRefresh, remaining), claims.subject(), claims.sessionId());
    }

    private boolean revokedBeforeRotation(TokenClaims claims) {
        try {
            return revocationChecker.isRevoked(claims);
        } catch (RevocationCheckFailedException e) {
            log.warn("refresh rotation closed: revocation lookup failed sid={} cause={}", shortSid(claims.sessionId()), e.getMessage());
            throw new RevocationLookupUnavailableException();
        }
    }

    /** 회전 뒤 검사. 조회 불가도 폐기로 본다 — 재발급은 닫는 경로이고, 새 원문을 클라이언트가 못 받으면 그 세션은 어차피 죽는다. */
    private boolean revokedDuringRotation(TokenClaims claims) {
        try {
            return revocationChecker.isRevoked(claims);
        } catch (RevocationCheckFailedException e) {
            log.warn("post-rotation revocation lookup failed sid={} cause={}", shortSid(claims.sessionId()), e.getMessage());
            return true;
        }
    }

    /** 표식·저장소를 각각 시도. 저장소 예외는 WARN 으로만 — 호출자가 어차피 401 을 낼 자리에서 쓴다. */
    private void revokeSessionBestEffort(UUID sessionId, String why) {
        try {
            revoke(sessionId);
        } catch (DataAccessException e) {
            log.warn("{}: session revocation incomplete sid={} cause={}", why, shortSid(sessionId), e.getClass().getSimpleName());
        }
    }

    public void revoke(TokenClaims accessClaims) {
        revoke(accessClaims.sessionId());
    }

    /**
     * 세션 하나를 끊는다. 폐기 표식을 먼저 심고(필터가 읽는 것은 이 표식이라 액세스 토큰이 즉시 죽는다),
     * 그다음 리프레시를 끊는다. 셋은 각각 시도한다 — 하나가 실패해도 나머지는 한다. 저장소 예외만 모아 마지막에 던지고
     * 프로그래밍 오류는 삼키지 않는다. 호출자(로그아웃)는 하나라도 실패하면 성공으로 답하지 않는다.
     */
    public void revoke(UUID sessionId) {
        DataAccessException failure = null;
        try {
            revocations.revokeSession(sessionId, properties.accessTokenValidity());
        } catch (DataAccessException e) {
            log.warn("logout: revocation mark not written sid={} cause={}", shortSid(sessionId), e.getClass().getSimpleName());
            failure = e;
        }
        try {
            refreshTokens.revokeSession(sessionId);
        } catch (DataAccessException e) {
            log.warn("logout: refresh rows not revoked sid={} cause={}", shortSid(sessionId), e.getClass().getSimpleName());
            failure = failure == null ? e : failure;
        }
        try {
            adminRefreshTokens.delete(sessionId);
        } catch (DataAccessException e) {
            log.warn("logout: admin refresh not deleted sid={} cause={}", shortSid(sessionId), e.getClass().getSimpleName());
            failure = failure == null ? e : failure;
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 제재·탈퇴. 표식을 먼저 심어 액세스를 끊고(같은 초 발급까지 무효), 그다음 DB 의 살아 있는 리프레시를 전부 폐기한다.
     * 표식만으로도 재발급은 막히지만(회전 전 검사) 행을 남겨 두면 표식 TTL 이 지난 뒤 되살아난다.
     */
    public void revokeAll(String subject) {
        revocations.revokeAll(subject, properties.refreshTokenValidity());
        refreshTokens.revokeAllOf(subject);
    }

    private TokenClaims claimsOf(RefreshTokenStore.Session session) {
        return new TokenClaims(session.subject(), session.sessionId(), NO_TOKEN_ID, Role.USER,
                TokenType.REFRESH, session.issuedAt(), session.expiresAt());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static String shortSid(UUID sessionId) {
        return sessionId.toString().substring(0, 8);
    }

    /** 발급 결과. refreshTokenMaxAge 는 쿠키 Max-Age 다 — 회전 때는 남은 만료라 14일보다 짧다. */
    public record IssuedTokens(String accessToken, String refreshToken, Duration refreshTokenMaxAge) {
    }

    /** 회전 결과. subject·sessionId 는 응답을 만들 때 쓴다 — 불투명 리프레시에는 주인이 적혀 있지 않다. */
    public record Rotated(IssuedTokens tokens, String subject, UUID sessionId) {
    }
}
