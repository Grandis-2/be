package com.grandis.nova.member.auth.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.JsonAuthFailureHandlers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 관리자 자격증명(`admin.username` · `admin.password-hash`)이 바뀌면 이미 발급된 관리자 세션을 전부 끊는다(D-16).
 *
 * JWT 는 서버가 들고 있지 않아 비밀번호를 바꿔도 저절로 죽지 않는다. 그래서 자격증명의 지문(SHA-256)을 Redis 에 두고,
 * 기동 때 지문이 저장된 것과 다르면 {@code revokeAll("admin")} — 관리자 subject 의 not-before 표식이라 이전에 발급된 액세스 · 리프레시가 즉시 거부된다.
 * 처음 기동(저장된 지문 없음)은 폐기 없이 기록만 한다.
 *
 * <p><b>롤링 배포.</b> 새 태스크가 뜨는 순간 폐기가 한 번 되지만, 아직 떠 있는 옛 태스크는 옛 해시로 로그인을 계속 받고 그 토큰의 iat 는
 * 표식 뒤라 폐기를 비껴간다(실측). 그래서 관리자 로그인은 저장된 지문이 자기 지문과 다르면 거절한다({@link #requireCurrentForLogin}) —
 * 옛 태스크가 교체 뒤 발급을 멈춰 창이 닫힌다. 롤백은 옛 태스크의 기동이 다시 기록 · 폐기하므로 그대로 동작한다.
 *
 * <p><b>Redis 가 죽어 있으면.</b> 기동은 막지 않고 1분마다 다시 시도한다. Redis 가 통째로 죽은 동안은 `/api/v1/admin/**` 가 폐기 조회 실패에
 * 닫히는 경로(D-2)라 관리자 요청 자체가 401 이고, 관리자 로그인도 지문을 못 읽어 503 이다. Redis 가 돌아온 뒤 이 확인이 끝나기 전(재시도 주기 1분)이
 * 옛 세션이 통할 수 있는 창의 상한이다. 조회는 되고 **쓰기만** 실패하는 장애(READONLY · noeviction)는 다르다 — 체커는 통과하므로 옛 세션이 살고,
 * 폐기 · 기록이 실패해 미확인으로 남아 재시도가 이어진다. 그 상태는 로그(ERROR)로만 드러난다.
 */
@Component
public class AdminCredentialRotationGuard {

    private static final Logger log = LoggerFactory.getLogger(AdminCredentialRotationGuard.class);

    private final AdminProperties properties;
    private final AdminCredentialFingerprintStore store;
    private final TokenService tokens;
    private final String current;
    private final AtomicBoolean confirmed = new AtomicBoolean(false);

    public AdminCredentialRotationGuard(AdminProperties properties, AdminCredentialFingerprintStore store, TokenService tokens) {
        this.properties = properties;
        this.store = store;
        this.tokens = tokens;
        this.current = fingerprint(properties);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        tryEnsureCurrent();
    }

    /** 기동 때 Redis 가 죽어 있었으면 확인이 끝날 때까지 1분마다. 끝나면 아무것도 안 한다. */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void retry() {
        if (!confirmed.get()) {
            tryEnsureCurrent();
        }
    }

    public boolean isConfirmed() {
        return confirmed.get();
    }

    /**
     * 지문을 대조하고, 다르면 관리자 세션 전부를 끊은 뒤 새 지문을 기록한다. 순서가 중요하다 — 기록이 먼저면 폐기가 실패했을 때
     * 다음 기동이 "이미 확인됨" 으로 보고 넘어간다. 저장소 예외는 그대로 던진다(호출자가 재시도).
     */
    public void ensureCurrent() {
        Optional<String> stored = store.find();
        if (stored.isEmpty()) {
            store.save(current);
            log.info("admin credential fingerprint recorded (first run)");
        } else if (!stored.get().equals(current)) {
            tokens.revokeAll(AdminLoginService.ADMIN_SUBJECT);
            store.save(current);
            log.warn("admin credentials changed; all admin sessions revoked");
        }
        confirmed.set(true);
    }

    /**
     * 관리자 로그인 직전 — 저장된 지문이 이 인스턴스의 지문과 다르면 이 인스턴스는 옛 자격증명으로 떠 있는 것이다(롤링 배포 중 옛 태스크).
     * 발급하면 방금 한 폐기를 비껴가는 세션이 생기므로 503 retryable 로 거절한다 — 다음 시도는 새 태스크에 닿는다.
     * 지문을 못 읽어도(Redis 장애) 같은 503 — 관리자 로그인은 드물고, 모르는 채 발급하지 않는다. 저장된 지문이 없으면(첫 기동 전) 통과.
     */
    public void requireCurrentForLogin() {
        Optional<String> stored;
        try {
            stored = store.find();
        } catch (DataAccessException e) {
            log.warn("admin login refused: credential fingerprint unreadable cause={}", e.getClass().getSimpleName());
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE, JsonAuthFailureHandlers.RETRYABLE_DETAILS);
        }
        if (stored.isPresent() && !stored.get().equals(current)) {
            log.warn("admin login refused: this instance holds stale admin credentials (rolling deploy?)");
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE, JsonAuthFailureHandlers.RETRYABLE_DETAILS);
        }
    }

    private void tryEnsureCurrent() {
        try {
            ensureCurrent();
        } catch (DataAccessException e) {
            log.error("admin credential check failed (redis); retrying every minute cause={}", e.getClass().getSimpleName());
        }
    }

    /** username 과 bcrypt 해시를 함께 — 둘 중 하나만 바뀌어도 교체다. 해시 자체를 Redis 에 두지 않으려고 한 번 더 해시한다. */
    public static String fingerprint(AdminProperties properties) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(properties.username().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(properties.passwordHash().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
