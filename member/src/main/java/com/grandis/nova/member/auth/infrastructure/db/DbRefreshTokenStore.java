package com.grandis.nova.member.auth.infrastructure.db;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.member.auth.application.RefreshTokenStore;
import com.grandis.nova.member.auth.application.RefreshTokens;
import com.grandis.nova.common.security.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 *     · 없음                    → 거절
 *     · 폐기됨                  → 같은 체인에 폐기 안 된 행이 있으면(유예가 버린 다음 토큰이 다시 옴) 재사용, 아니면 거절
 *                                 (이 판정만 행 잠금 전에 한다 — reuseOfAbandonedSuccessor)
 *     · 만료 뒤 탐지 창도 지남   → 만료로 거절(체인은 건드리지 않는다)
 *     · 이미 교체된 토큰         → 교체 뒤 30초 안이고 그 다음 토큰이 아직 안 쓰였으면 다시 회전(아래 유예)
 *                                 아니면 탈취로 판단. 같은 체인의 살아 있는 행을 전부 폐기하고 거절
 *     · 만료됨                  → 거절
 *     · 정상                    → 이 행에 교체 시각을 찍고, 같은 체인으로 새 행을 만든다
 * </pre>
 *
 * 시각은 초로 자른다. 회원 단위 폐기 표식이 epoch 초라 발급 시각도 같은 정밀도여야 경계가 한쪽으로만 기운다.
 *
 * 절대 만료는 회전해도 늘지 않는다 — 새 행이 원 행의 만료 시각을 그대로 물려받는다.
 *
 * <p><b>회전 유예.</b> 새로고침을 연타하면 브라우저가 앞 페이지의 재발급 응답을 버려, 서버는 회전했는데 새 쿠키가 저장되지 않고 다음 페이지가 옛
 * 원문으로 다시 온다. 그래서 교체 뒤 {@link #ROTATION_GRACE} 안에 같은 옛 원문이 오면, 그 원문이 만든 다음 토큰(rotated_to)이 아직 한 번도
 * 쓰이지 않았을 때에 한해 다음 토큰을 폐기하고 새로 회전한다 — 살아 있는 토큰은 늘 하나다. 다음 토큰이 이미 쓰였으면(그 뒤로 회전했으면) 지금처럼
 * 재사용이다. 유예 창은 처음 교체 시각부터라 다시 회전해도 늘지 않는다. 시각을 초로 잘라 저장하므로 실제 창은 29~30초다(교체 시각의 소수부만큼
 * 짧다). 30초는 Okta 의 기본 유예(0~60초)다.
 * <ul>
 *   <li>유예가 버린 다음 토큰이 다시 오면 재사용으로 체인을 끊는다 — 정상 연타에서는 버려진 토큰이 다시 오지 않고(브라우저가 받지 못했다), 온다면
 *       두 주체가 같은 체인을 쓰고 있다는 뜻이다. 그래서 옛 원문을 훔친 쪽이 교체 뒤 30초 안에 먼저 써도, 원 소유자가 자기 토큰을 내는 순간 양쪽이 끊기고
 *       WARN 이 남는다(Auth0 도 무효화된 토큰이 다시 오면 그 체인을 폐기한다). 그 순간 로그아웃이 같은 체인을 먼저 끊으면 REVOKED 로 끝나 WARN 은 없다.</li>
 *   <li>한계: 같은 옛 원문이 <b>동시에</b> 여럿 오면 행 잠금이 줄 세워 커밋 순서상 마지막 토큰만 산다. 응답이 커밋 순서와 다르게 도착해 브라우저에
 *       앞 토큰이 남으면 다음 재발급이 위 재사용으로 끊긴다. 서버는 원문을 갖지 않아 같은 토큰을 다시 줄 수 없다. 앞 페이지의 요청이 먼저 잠금을 잡는
 *       보통의 새로고침 연타라면 서버에서는 마지막 커밋이 마지막 요청의 토큰이다. 브라우저가 어느 응답의 쿠키를 저장하는지는 재지 않았다(추론).</li>
 * </ul>
 *
 * <p><b>탐지 창.</b> 재사용 판정은 만료 판정보다 먼저다 — 만료 직후 교체된 토큰이 다시 오면 체인을 끊고 그 세션의 액세스 토큰도 폐기 표식으로 죽여야
 * 하기 때문이다. 그 필요는 만료 뒤 액세스 유효기간까지다(그 뒤엔 체인이 낸 액세스 토큰이 다 죽었다). 창이 지난 토큰은 재사용이어도 체인 폐기 없이
 * 만료로 거절한다 — 그 체인은 {@link RefreshTokenCleanup} 이 지우는 중일 수 있고, 체인 폐기(`ix_refresh_family` 순서 잠금)와 정리의 기본키 삭제가
 * 맞물리면 교착해 이 요청이 희생됐다(리뷰 실측). 정리는 이 창보다 {@link RefreshTokenCleanup#MARGIN_AFTER_DETECTION} 더 뒤의 행만 지운다.
 */
@Repository
public class DbRefreshTokenStore implements RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(DbRefreshTokenStore.class);

    /** 교체 뒤 같은 옛 원문을 재사용이 아니라 다시 회전으로 받아 주는 시간. 위 회전 유예. 관리자 저장소와 같은 값. */
    static final Duration ROTATION_GRACE = RefreshTokens.ROTATION_GRACE;

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
    public List<UUID> replace(UUID sessionId, String subject, String rawToken, Instant expiresAt) {
        UUID customerId = customerIdOf(subject).orElseThrow(() ->
                new IllegalStateException("회원 리프레시 저장소에 회원 id 가 아닌 subject 가 왔다: " + subject));
        // 회원 행을 먼저 잠근다 — 같은 회원의 로그인이 둘 오면 늦은 쪽이 기다렸다가 앞 쪽이 커밋한 세션을 보고 끊는다. 리프레시 행만 잠가서는
        // 줄이 서지 않는다: 첫 로그인은 잠글 행이 없고, 기다린 쪽은 앞 쪽이 넣은 새 행을 못 본다(READ COMMITTED).
        // 대가: 그 회원 행에 외래키 공유 잠금을 쥔 트랜잭션(장바구니 · 주문 · 예약 · 응모 · 리뷰 INSERT, 리프레시 회전 · 폐기)이 끝날 때까지 로그인이
        // 기다린다(교착은 아니다 — 리뷰 실측: 장바구니 INSERT 가 4초 쥐면 로그인도 4초). 오픈 피크에 같은 회원의 긴 접수가 있으면 그만큼 늦다
        if (rows.lockCustomer(UuidBinary.toBytes(customerId)).isEmpty()) {
            throw new IllegalStateException("없는 회원의 로그인: " + customerId);
        }
        Instant now = now();
        // 탐지 창 안의 행만 — 창이 지난 체인은 막을 액세스 토큰이 없고 정리가 지우는 중일 수 있다(revokeAllOf 와 같은 이유)
        Instant windowStart = now.minus(detectionWindow);
        List<UUID> replaced = rows.findLiveFamiliesOf(customerId, windowStart).stream().map(UUID::fromString).toList();
        List<UUID> live = rows.findLiveIdsOf(customerId, windowStart);
        if (!live.isEmpty()) {
            // 이미 교체된(rotated) 행까지 폐기한다. 그래서 끊긴 체인에 탈취한 옛 원문이 와도 재사용 탐지(경고) 대신 REVOKED 로 끝난다 —
            // 체인이 이미 죽어 안전은 같고, 탈취 신호만 남지 않는다
            rows.revokeByIds(live, now);
        }
        rows.save(RefreshToken.issue(customerId, sessionId, RefreshTokens.hash(rawToken), now, expiresAt));
        return replaced;
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
    public Rotation rotate(String presentedRawToken, String newRawToken) {
        Instant now = now();
        byte[] hash = RefreshTokens.hash(presentedRawToken);
        // 회원 행 → 리프레시 행 순서로 잠근다(로그인 replace 와 같은 순서). 새 행 INSERT 의 외래키 검사가 회원 행에 공유 잠금을 거는데, 리프레시 행을
        // 먼저 잠그면 로그인(회원 행 → 이 리프레시 행 폐기)과 서로 기다려 교착한다(실측 1213 — 로그인이 희생됐다)
        Optional<UUID> owner = rows.findOwnerByTokenHash(hash);
        if (owner.isEmpty() || rows.shareCustomer(UuidBinary.toBytes(owner.get())).isEmpty()) {
            return Rotation.rejected(Rotation.Status.NOT_FOUND, null, null);
        }
        Optional<Rotation> abandoned = reuseOfAbandonedSuccessor(hash, owner.get(), now);
        if (abandoned.isPresent()) {
            return abandoned.get();
        }
        Optional<RefreshToken> found = rows.findByTokenHashForUpdate(hash);
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
            Optional<Rotation> retried = rotateAgainWithinGrace(row, newRawToken, now);
            if (retried.isPresent()) {
                return retried.get();
            }
            // 이미 교체된 원문이 다시 왔다. 원 소유자와 탈취자 중 누가 냈든 체인을 통째로 끊는다.
            // 여기서 예외를 던지면 이 폐기가 롤백된다 — 그래서 값으로 돌려주고 401 은 호출자가 만든다.
            int revoked = rows.revokeFamily(row.getFamilyId(), now);   // 위에서 이 체인이 탐지 창 안임을 봤다(만료는 체인 공통)
            log.warn("refresh reuse: family revoked sid={} rows={}", shortSid(sessionId), revoked);
            return Rotation.rejected(Rotation.Status.REUSED, sessionId, subject);
        }
        if (!row.getExpiresAt().isAfter(now)) {
            return Rotation.rejected(Rotation.Status.EXPIRED, sessionId, subject);
        }
        RefreshToken next = rows.save(RefreshToken.issue(row.getCustomerId(), sessionId, RefreshTokens.hash(newRawToken),
                now, row.getExpiresAt()));
        row.markRotated(now, next.getId());
        return new Rotation(Rotation.Status.ROTATED, sessionId, subject, row.getExpiresAt());
    }

    /**
     * 폐기된 원문인데 같은 체인에 폐기 안 된 행이 남았으면 유예 회전이 버린 다음 토큰이다 — 두 주체가 같은 체인을 쓴다는 뜻이라 체인을 끊고 재사용이다.
     * 로그아웃 · 새 로그인은 체인을 통째로 끊어 여기 걸리지 않는다. 회원 전체 폐기는 잠그지 않고 고른 뒤 끊어, 그 사이 회전이 만든 새 행이 남으면
     * 여기 걸릴 수 있다(원래 있던 틈 — 그 세션의 액세스는 회원 단위 표식이 막는다).
     * <p>원문 행을 <b>잠그기 전에</b> 잠그지 않는 읽기로 본다. revoked_at 은 한 번 찍히면 지워지지 않아 읽은 뒤 바뀌지 않는다. 원문 행을 쥔 채 체인을
     * 잠그면 체인을 처음부터 잠그는 로그아웃 · 재사용 탐지와 순서가 엇갈려 1213 이 났다(리뷰 실측 60/60). 지금은 회원 행 공유 잠금만 쥐고 체인을
     * 로그아웃과 같은 순서로 잠근다. 체인이 살았는지는 READ COMMITTED 의 최신 커밋으로 본다 — 반복 읽기면 첫 읽기의 스냅숏이라 방금 끊긴 체인을
     * 산 것으로 본다(리뷰 실측). member 는 READ COMMITTED 다(application.yml.example).
     */
    private Optional<Rotation> reuseOfAbandonedSuccessor(byte[] hash, UUID owner, Instant now) {
        Optional<String> familyId = rows.findRevokedFamilyByTokenHash(hash);
        if (familyId.isEmpty() || rows.countLiveInFamily(familyId.get(), now.minus(detectionWindow)) == 0) {
            return Optional.empty();
        }
        UUID sessionId = UUID.fromString(familyId.get());
        int revoked = rows.revokeFamily(familyId.get(), now);
        log.warn("refresh reuse: abandoned successor came back, family revoked sid={} rows={}", shortSid(sessionId), revoked);
        return Optional.of(Rotation.rejected(Rotation.Status.REUSED, sessionId, String.valueOf(owner)));
    }

    /**
     * 회전 유예(클래스 주석). 옛 행은 잠겨 있다. 다음 토큰을 잠그고 아직 안 쓰였으면 새 토큰을 만들어 옛 행이 그것을 가리키게 한 뒤 다음 토큰을 폐기한다.
     * 아니면 빈 값 — 호출자가 재사용으로 처리한다.
     */
    private Optional<Rotation> rotateAgainWithinGrace(RefreshToken row, String newRawToken, Instant now) {
        if (row.getRotatedTo() == null || !now.isBefore(row.getRotatedAt().plus(ROTATION_GRACE)) || !row.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        Optional<RefreshToken> successor = rows.findByIdForUpdate(row.getRotatedTo());
        // revokedAt 은 방어다 — 옛 행이 살아 있는데 다음 토큰만 폐기된 상태는 유예 경로가 만들고, 그때 옛 행은 새 토큰을 가리킨다
        if (successor.isEmpty() || successor.get().getRotatedAt() != null || successor.get().getRevokedAt() != null) {
            return Optional.empty();
        }
        UUID unused = successor.get().getId();
        RefreshToken next = rows.save(RefreshToken.issue(row.getCustomerId(), row.getSessionId(), RefreshTokens.hash(newRawToken),
                now, row.getExpiresAt()));
        row.replaceSuccessor(next.getId());
        rows.revokeByIds(List.of(unused), now);   // 먼저 위 INSERT · UPDATE 를 내보낸 뒤(flushAutomatically) 폐기한다
        log.info("refresh rotated again within grace sid={}", shortSid(row.getSessionId()));
        return Optional.of(new Rotation(Rotation.Status.ROTATED, row.getSessionId(), String.valueOf(row.getCustomerId()), row.getExpiresAt()));
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
        // 회원 행 → 리프레시 행 순서(로그인 replace 와 같다). 폐기 UPDATE 가 외래키 검사로 회원 행 공유 잠금을 거는데, 체인 행을 먼저 잠그면
        // 그 회원의 로그인과 교착한다(실측 1213, 로그인 희생 — 이미 쓴 카카오 code 는 다시 못 써서 사용자가 로그인을 처음부터 다시 한다)
        Optional<UUID> owner = rows.findOwnerOfFamily(sessionId.toString());
        if (owner.isEmpty() || rows.shareCustomer(UuidBinary.toBytes(owner.get())).isEmpty()) {
            return;
        }
        rows.revokeFamily(sessionId.toString(), now);
    }

    @Override
    @Transactional
    public void revokeAllOf(String subject) {
        // 관리자 세션은 이 표에 행이 없다(회원 외래키). 조용히 지나간다.
        // 탐지 창 안의 행만 잠그지 않고 골라 기본키로 폐기한다 — 창이 지난 행은 정리가 지우는 중일 수 있고, 폐기해도 막을 액세스 토큰이 없다
        customerIdOf(subject).ifPresent(customerId -> {
            // 회원 행 → 리프레시 행 순서(revokeSession 과 같은 이유). 회원 행이 없으면 폐기할 행도 없다
            if (rows.shareCustomer(UuidBinary.toBytes(customerId)).isEmpty()) {
                return;
            }
            Instant now = now();
            List<UUID> live = rows.findLiveIdsOf(customerId, now.minus(detectionWindow));
            if (!live.isEmpty()) {
                // 고른 뒤 다른 폐기(로그아웃 · 재사용 탐지)가 먼저 끊었으면 그 행은 0 으로 센다 — 이미 끊긴 것이라 실패가 아니다
                int revoked = rows.revokeByIds(live, now);
                log.info("member-wide revoke customer={} selected={} revoked={}", customerId, live.size(), revoked);
            }
        });
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static Optional<UUID> customerIdOf(String subject) {
        try {
            return Optional.of(UUID.fromString(subject));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String shortSid(UUID sessionId) {
        return sessionId.toString().substring(0, 8);
    }
}
