package com.grandis.nova.member.auth.infrastructure.db;

import com.grandis.nova.member.auth.application.ClientInfo;
import com.grandis.nova.member.auth.application.RefreshTokenStore;
import com.grandis.nova.member.auth.application.RefreshTokens;
import com.grandis.nova.common.security.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 리프레시의 정본 저장소. `shop.refresh_tokens` 표 주석의 회전 절차를 옮기고, 탐지 창이 지난 토큰은 체인을 건드리지 않는 예외를 더했다(아래).
 *
 * <pre>
 *   재발급 요청 → 받은 원문을 해시해 행을 찾는다(행 잠금)
 *     · 없음 · 폐기됨           → 거절
 *     · 만료 뒤 탐지 창도 지남   → 만료로 거절(체인은 건드리지 않는다)
 *     · 이미 교체된 토큰         → 탈취로 판단. 같은 체인의 살아 있는 행을 전부 폐기하고 거절
 *     · 만료됨                  → 거절
 *     · 정상                    → 이 행에 교체 시각을 찍고, 같은 체인으로 새 행을 만든다
 * </pre>
 *
 * 시각은 초로 자른다. 회원 단위 폐기 표식이 epoch 초라 발급 시각도 같은 정밀도여야 경계가 한쪽으로만 기운다.
 *
 * 절대 만료는 회전해도 늘지 않는다 — 새 행이 원 행의 만료 시각을 그대로 물려받는다.
 *
 * <p><b>탐지 창.</b> 재사용 판정은 만료 판정보다 먼저다 — 만료 직후 교체된 토큰이 다시 오면 체인을 끊고 그 세션의 액세스 토큰도 폐기 표식으로 죽여야
 * 하기 때문이다. 그 필요는 만료 뒤 액세스 유효기간까지다(그 뒤엔 체인이 낸 액세스 토큰이 다 죽었다). 창이 지난 토큰은 재사용이어도 체인 폐기 없이
 * 만료로 거절한다 — 그 체인은 {@link RefreshTokenCleanup} 이 지우는 중일 수 있고, 체인 폐기(`ix_refresh_family` 순서 잠금)와 정리의 기본키 삭제가
 * 맞물리면 교착해 이 요청이 희생됐다(리뷰 실측). 정리는 이 창보다 {@link RefreshTokenCleanup#MARGIN_AFTER_DETECTION} 더 뒤의 행만 지운다.
 */
@Repository
public class DbRefreshTokenStore implements RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(DbRefreshTokenStore.class);

    private final RefreshTokenRepository rows;
    private final Clock clock;
    private final Duration detectionWindow;

    public DbRefreshTokenStore(RefreshTokenRepository rows, Clock clock, JwtProperties jwt) {
        this.detectionWindow = jwt.accessTokenValidity();
        this.rows = rows;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void save(UUID sessionId, String subject, String rawToken, Instant expiresAt, ClientInfo client) {
        long customerId = customerIdOf(subject).orElseThrow(() ->
                new IllegalStateException("회원 리프레시 저장소에 회원 번호가 아닌 subject 가 왔다: " + subject));
        rows.save(RefreshToken.issue(customerId, sessionId, RefreshTokens.hash(rawToken),
                now(), expiresAt, client.ip(), client.userAgent()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Session> find(String rawToken) {
        return rows.findByTokenHash(RefreshTokens.hash(rawToken))
                .map(row -> new Session(row.getSessionId(), String.valueOf(row.getCustomerId()),
                        row.getCreatedAt(), row.getExpiresAt()));
    }

    @Override
    @Transactional
    public Rotation rotate(String presentedRawToken, String newRawToken, ClientInfo client) {
        Instant now = now();
        Optional<RefreshToken> found = rows.findByTokenHashForUpdate(RefreshTokens.hash(presentedRawToken));
        if (found.isEmpty()) {
            return Rotation.rejected(Rotation.Status.NOT_FOUND, null, null);
        }
        RefreshToken row = found.get();
        UUID sessionId = row.getSessionId();
        String subject = String.valueOf(row.getCustomerId());
        if (row.getRevokedAt() != null) {
            return Rotation.rejected(Rotation.Status.REVOKED, sessionId, subject);
        }
        if (!row.getExpiresAt().isAfter(now.minus(detectionWindow))) {
            // 만료 뒤 탐지 창(액세스 유효기간)도 지났다 — 체인을 건드리지 않고 거절한다(정리와 교착하지 않게)
            return Rotation.rejected(Rotation.Status.EXPIRED, sessionId, subject);
        }
        if (row.getRotatedAt() != null) {
            // 이미 교체된 원문이 다시 왔다. 원 소유자와 탈취자 중 누가 냈든 체인을 통째로 끊는다.
            // 여기서 예외를 던지면 이 폐기가 롤백된다 — 그래서 값으로 돌려주고 401 은 호출자가 만든다.
            int revoked = rows.revokeFamily(row.getFamilyId(), now);   // 위에서 이 체인이 탐지 창 안임을 봤다(만료는 체인 공통)
            log.warn("refresh reuse: family revoked sid={} rows={}", shortSid(sessionId), revoked);
            return Rotation.rejected(Rotation.Status.REUSED, sessionId, subject);
        }
        if (!row.getExpiresAt().isAfter(now)) {
            return Rotation.rejected(Rotation.Status.EXPIRED, sessionId, subject);
        }
        row.markRotated(now);
        rows.save(RefreshToken.issue(row.getCustomerId(), sessionId, RefreshTokens.hash(newRawToken),
                now, row.getExpiresAt(), client.ip(), client.userAgent()));
        return new Rotation(Rotation.Status.ROTATED, sessionId, subject, row.getExpiresAt());
    }

    @Override
    @Transactional
    public void revokeSession(UUID sessionId) {
        Instant now = now();
        // 탐지 창이 지난 체인은 건드리지 않는다 — 막을 액세스 토큰이 없고, 정리가 그 행을 지우는 중이면 폐기 UPDATE 가 그 잠금을 기다린다
        // (리뷰 실측 6초). 먼저 잠그지 않고 만료를 본다. 만료는 바뀌지 않고 창은 앞으로만 가므로 본 뒤 창 안으로 돌아오는 일은 없다
        Instant expiry = rows.expiryOfFamily(sessionId.toString());
        if (expiry == null || !expiry.isAfter(now.minus(detectionWindow))) {
            return;
        }
        rows.revokeFamily(sessionId.toString(), now);
    }

    @Override
    @Transactional
    public void revokeAllOf(String subject) {
        // 관리자 세션은 이 표에 행이 없다(회원 외래키). 조용히 지나간다.
        customerIdOf(subject).ifPresent(customerId -> rows.revokeAllOf(customerId, now()));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static Optional<Long> customerIdOf(String subject) {
        try {
            return Optional.of(Long.parseLong(subject));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String shortSid(UUID sessionId) {
        return sessionId.toString().substring(0, 8);
    }
}
