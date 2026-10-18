package com.grandis.nova.member.auth.application;

import com.grandis.nova.common.security.InvalidTokenException;
import com.grandis.nova.common.security.JwtProperties;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationCheckFailedException;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.RevocationLookupUnavailableException;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.SessionReplacedException;
import com.grandis.nova.common.security.TokenClaims;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.member.MySqlLockFailures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
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
    /** 재사용 세션의 체인 폐기를 교착에서 다시 하는 최대 횟수. */
    static final int REVOKE_ATTEMPTS = 3;

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

    /**
     * 로그인. 새 sid 하나에 액세스·리프레시 한 쌍. 회원은 DB 에 행을, 관리자는 Redis 에 jti 를 남긴다.
     *
     * 회원은 한 번에 세션 하나다 — 새 로그인이 그 회원의 살아 있는 세션을 끊는다(리프레시는 같은 트랜잭션에서 폐기, 액세스는 커밋 뒤 폐기 표식).
     * 끊긴 세션의 다음 요청 · 재발급은 401 이고, 표식이 심긴 뒤면 `details.reason = SESSION_REPLACED` 가 실린다. 이유가 없는 401 도 있다 —
     * 표식을 못 심었을 때, 로그인 커밋과 표식 사이(수 ms)에 재발급이 판정했을 때. 표식을 못 심어도 로그인은 성공한다 — 끊긴 세션의 리프레시는
     * 이미 죽었고, 남은 액세스 토큰은 만료(액세스 유효기간)까지만 산다(그 WARN 이 경보 재료다). 관리자는 끊지 않는다.
     */
    public IssuedTokens issue(String subject, Role role) {
        UUID sessionId = UUID.randomUUID();
        String access = provider.create(subject, role, sessionId, TokenType.ACCESS);
        Duration validity = properties.refreshTokenValidity();
        if (role == Role.ADMIN) {
            String refresh = provider.create(subject, role, sessionId, TokenType.REFRESH);
            adminRefreshTokens.save(sessionId, provider.parse(refresh).tokenId(), validity);
            return new IssuedTokens(access, refresh, validity);
        }
        String refresh = RefreshTokens.newToken();
        List<UUID> replaced = refreshTokens.replace(sessionId, subject, refresh, now().plus(validity));
        replaced.forEach(old -> markReplaced(old, validity));
        if (!replaced.isEmpty()) {
            log.info("login replaced sessions subject={} sessions={}", subject, replaced.size());
        }
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
    public Rotated rotate(String presentedRefreshToken, Role role) {
        return role == Role.ADMIN
                ? rotateAdmin(presentedRefreshToken)
                : rotateUser(presentedRefreshToken);
    }

    private Rotated rotateUser(String presented) {
        RefreshTokenStore.Session session = refreshTokens.find(presented)
                .orElseThrow(() -> new InvalidTokenException("refresh token not found"));
        TokenClaims asClaims = claimsOf(session);
        if (revokedBeforeRotation(asClaims)) {
            throw rejectRevoked(asClaims, "refresh of revoked session sid=" + shortSid(session.sessionId()));
        }

        String newRefresh = RefreshTokens.newToken();
        RefreshTokenStore.Rotation rotation = refreshTokens.rotate(presented, newRefresh);
        if (rotation.status() == RefreshTokenStore.Rotation.Status.REUSED) {
            cutReusedSession(rotation.sessionId(), Role.USER);
            log.warn("refresh reuse detected, session revoked sid={}", shortSid(rotation.sessionId()));
            throw new InvalidTokenException("refresh reuse sid=" + shortSid(rotation.sessionId()));
        }
        if (rotation.status() == RefreshTokenStore.Rotation.Status.REVOKED) {
            // 회전 전 검사와 행 잠금 사이에 새 로그인이 이 세션을 끊었을 수 있다. 표식은 로그인 커밋 뒤에 심기므로 아직 없으면 이유 없는 401 이다
            throw rejectRevoked(asClaims, "refresh rejected: REVOKED");
        }
        if (rotation.status() != RefreshTokenStore.Rotation.Status.ROTATED) {
            throw new InvalidTokenException("refresh rejected: " + rotation.status());
        }

        if (revokedDuringRotation(asClaims)) {
            revokeSessionBestEffort(session.sessionId(), "revoked during rotation");
            // 회전 커밋 뒤 새 로그인이 이 세션을 끊었으면 이유를 싣는다(표식은 덮지 않는다 — RevocationStore.revokeSession)
            throw rejectRevoked(asClaims, "session revoked during rotation sid=" + shortSid(session.sessionId()));
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
            cutReusedSession(claims.sessionId(), Role.ADMIN);
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

    /** 끊긴 세션의 재발급 401. 새 로그인에 밀린 세션이면 이유를 싣는다. 이유 조회가 안 되면 이유 없는 401 — 거절은 이미 정해졌다. */
    private RuntimeException rejectRevoked(TokenClaims claims, String reason) {
        try {
            if (revocationChecker.isReplaced(claims)) {
                return new SessionReplacedException();
            }
        } catch (RevocationCheckFailedException e) {
            log.warn("refresh rejection reason lookup failed sid={} cause={}", shortSid(claims.sessionId()), e.getMessage());
        }
        return new InvalidTokenException(reason);
    }

    /** 새 로그인에 밀린 세션의 액세스 토큰을 끊는다. 실패는 WARN — 로그인은 성공시킨다(issue 문서). */
    private void markReplaced(UUID sessionId, Duration refreshTokenValidity) {
        try {
            revocations.replaceSession(sessionId, refreshTokenValidity);
        } catch (DataAccessException e) {
            log.warn("auth.login.replaced_mark_failed — replaced session's access token lives until expiry sid={} cause={}",
                    shortSid(sessionId), e.getClass().getSimpleName());
        }
    }

    /**
     * 재사용으로 판정된 세션을 끊는다. 저장소는 판정만 하고 체인을 폐기하지 않는다(교착을 피해 따로 커밋 — DbRefreshTokenStore 클래스 주석).
     * <ol>
     *   <li>표식을 <b>리프레시 수명</b>만큼 심는다(로그아웃은 액세스 수명). 재발급은 회전 전에 이 표식을 보므로, 표식이 섰다면 아래 체인 폐기가
     *       끝내 실패해도 그 체인은 수명 내내 재발급되지 않는다. 같은 세션의 로그아웃 표식이 먼저 있으면 덮지 않아 그 액세스 TTL 이다
     *       (RevocationStore.revokeSession). 표식 쓰기와 체인 폐기가 <b>둘 다</b> 실패하면 체인이 절대 만료까지 산다 — 회전 전
     *       읽기는 성공하고 바로 뒤 쓰기만 실패해야 하는 좁은 경우다(Redis 가 아예 죽어 있으면 회전 전 검사에서 닫힌다).</li>
     *   <li>체인 폐기 — 교착(1213)이면 {@value #REVOKE_ATTEMPTS} 번까지 다시 한다. 잠금 대기 초과(1205)는 다시 하지 않는다 — 표식이 이미 섰고, 대기
     *       초과를 거듭 기다리면 요청이 수 분 묶인다(리뷰 실측 150초). 로그인 · 로그아웃 · 다른 재사용 탐지 · 정리와 겹쳐 잰 교착은 0 이었다
     *       (리뷰 실측 — 회원 행 → 체인으로 같은 순서다). 재시도는 혹시 남은 교착에 대한 방어다.</li>
     *   <li>관리자 키 삭제.</li>
     * </ol>
     * 저장소 예외는 WARN 으로만 — 호출자가 어차피 401 을 낼 자리다.
     */
    private void cutReusedSession(UUID sessionId, Role role) {
        boolean marked = false;
        boolean chainRevoked = false;
        try {
            revocations.revokeSession(sessionId, properties.refreshTokenValidity());
            marked = true;
        } catch (DataAccessException e) {
            log.warn("refresh reuse: revocation mark not written sid={}", shortSid(sessionId), e);
        }
        for (int attempt = 1; ; attempt++) {
            try {
                refreshTokens.revokeSession(sessionId);
                chainRevoked = true;
                break;
            } catch (PessimisticLockingFailureException e) {
                if (!MySqlLockFailures.isDeadlock(e) || attempt >= REVOKE_ATTEMPTS) {
                    log.warn("refresh reuse: refresh rows not revoked after {} attempts sid={}", attempt, shortSid(sessionId), e);
                    break;
                }
            } catch (DataAccessException e) {
                log.warn("refresh reuse: refresh rows not revoked sid={}", shortSid(sessionId), e);
                break;
            }
        }
        boolean adminKeyDeleted = false;
        try {
            adminRefreshTokens.delete(sessionId);
            adminKeyDeleted = true;
        } catch (DataAccessException e) {
            log.warn("refresh reuse: admin refresh not deleted sid={}", shortSid(sessionId), e);
        }
        // 체인을 실제로 끊는 곳은 역할마다 다르다 — 회원은 DB 체인 폐기, 관리자는 Redis 키 삭제(관리자 sid 는 DB 행이 없어 DB 폐기는 늘 성공한다)
        boolean chainCut = role == Role.ADMIN ? adminKeyDeleted : chainRevoked;
        if (!marked && !chainCut) {
            // 표식도 체인 끊기도 없다 — 재사용된 체인이 절대 만료까지 재발급된다. 다른 WARN 과 갈라 경보할 수 있게 고정 이벤트 키로 남긴다
            log.error("auth.refresh.reuse_chain_alive — reused refresh chain left alive until absolute expiry sid={} role={}", shortSid(sessionId), role);
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
