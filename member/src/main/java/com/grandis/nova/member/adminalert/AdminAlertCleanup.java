package com.grandis.nova.member.adminalert;

import com.grandis.nova.member.adminalert.AdminAlertRules.TargetKind;
import com.grandis.nova.common.UuidBinary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 관리자 알림 정리(RefreshTokenCleanup 과 같은 방식). 두 가지를 한다.
 * - 보관: 해소된 지 retention(30일)이 지난 행을 지운다. 열린 알림은 기간과 상관없이 두고, 한 번 오는 소식은 발생 시각이 해소 시각이다.
 *   묶음(batchSize)씩 지울 id 를 잠그지 않고 고른 뒤 그 id 만 기본키로 지운다 — 여러 인스턴스가 겹쳐도 먼저 지운 쪽이 지우고 다른 쪽은 0행이다.
 * - 이름 다시 채우기: 받을 때 이름을 받지 못한 상품 · 회차 알림 중 refillWindow(하루) 안에 만든 것을 대상 단위로 최근 순 refillLimit 개 골라 다시 묻는다
 *   (같은 대상의 행이 많아도 한 자리다). 한 번 실패하면 그 주기는 멈춘다
 *   (공개 조회에 없는 대상은 건너뛴다. 상대가 내려가 있으면 나머지도 실패한다 — @Scheduled 스레드를 리프레시 정리와 나눠 쓰므로 오래 쥐지 않는다).
 *
 * 주기는 member.admin-alert.cleanup.cron(기본 10분마다, "-" 면 끈다).
 */
@Component
@EnableConfigurationProperties(AdminAlertCleanup.Settings.class)
class AdminAlertCleanup {

    private static final Logger log = LoggerFactory.getLogger(AdminAlertCleanup.class);

    private static final String EXPIRED = """
            SELECT id FROM admin_alerts WHERE status = 'RESOLVED' AND resolved_at < ? ORDER BY resolved_at LIMIT ?""";
    private static final String DELETE = "DELETE FROM admin_alerts WHERE status = 'RESOLVED' AND resolved_at < ? AND id IN (%s)";
    private static final String NAMELESS = """
            SELECT target_kind, target_id FROM admin_alerts
             WHERE target_name IS NULL AND target_kind IN ('PRODUCT', 'DRAW') AND created_at >= ?
             GROUP BY target_kind, target_id ORDER BY MAX(created_at) DESC LIMIT ?""";
    private static final String NAMELESS_OF_TARGET = """
            SELECT id FROM admin_alerts WHERE target_kind = ? AND target_id = ? AND target_name IS NULL FOR UPDATE""";
    private static final String FILL = """
            UPDATE admin_alerts SET target_name = ?, changed_at = ?, version = version + 1 WHERE id = ? AND target_name IS NULL""";

    /**
     * @param retention        해소 뒤 보관 기간
     * @param refillWindow     이름을 다시 묻는 범위(만든 지 이만큼 안)
     * @param refillLimit      한 번에 다시 묻는 대상 수
     */
    @ConfigurationProperties("member.admin-alert.cleanup")
    record Settings(@DefaultValue("30d") Duration retention, @DefaultValue("500") int batchSize,
                    @DefaultValue("20") int maxBatchesPerRun, @DefaultValue("1d") Duration refillWindow,
                    @DefaultValue("20") int refillLimit) {

        Settings {
            if (!retention.isPositive() || !refillWindow.isPositive() || batchSize <= 0 || maxBatchesPerRun <= 0 || refillLimit <= 0) {
                throw new IllegalArgumentException("member.admin-alert.cleanup 의 기간 · 묶음 · 횟수는 0 보다 커야 한다");
            }
        }
    }

    private final JdbcTemplate jdbc;
    private final TargetNames names;
    private final Clock clock;
    private final Settings settings;
    private final TransactionOperations transactions;

    AdminAlertCleanup(JdbcTemplate jdbc, TargetNames names, Clock clock, Settings settings, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.names = names;
        this.clock = clock;
        this.settings = settings;
        this.transactions = transactions;
    }

    @Scheduled(cron = "${member.admin-alert.cleanup.cron:0 */10 * * * *}")
    void scheduled() {
        int deleted = deleteExpired();
        int filled = refillNames();
        if (deleted > 0 || filled > 0) {
            log.info("관리자 알림 정리 — 지운 행 {}, 이름을 채운 대상 {}", deleted, filled);
        }
    }

    /** @return 지운 행 수. 묶음마다 따로 커밋한다 */
    int deleteExpired() {
        Object cutoff = AdminAlertWriter.utc(clock.instant().minus(settings.retention()));
        int total = 0;
        for (int batch = 0; batch < settings.maxBatchesPerRun(); batch++) {
            List<byte[]> ids = jdbc.query(EXPIRED, (rs, i) -> rs.getBytes(1), cutoff, settings.batchSize());
            if (ids.isEmpty()) {
                break;
            }
            Object[] args = new Object[ids.size() + 1];
            args[0] = cutoff;
            for (int i = 0; i < ids.size(); i++) {
                args[i + 1] = ids.get(i);
            }
            total += jdbc.update(DELETE.formatted(String.join(", ", Collections.nCopies(ids.size(), "?"))), args);
            if (ids.size() < settings.batchSize()) {
                break;
            }
        }
        return total;
    }

    /** @return 이름을 채운 대상 수 */
    int refillNames() {
        Instant now = clock.instant();
        List<List<String>> targets = jdbc.query(NAMELESS, (rs, i) -> List.of(rs.getString(1), rs.getString(2)),
                AdminAlertWriter.utc(now.minus(settings.refillWindow())), settings.refillLimit());
        int filled = 0;
        for (List<String> target : targets) {
            TargetKind kind = TargetKind.valueOf(target.get(0));
            TargetNames.Lookup lookup = names.find(kind, target.get(1));
            if (lookup.failed()) {
                break;
            }
            if (lookup.name() == null) {
                continue;
            }
            // 저장 중인 알림의 잠금을 기다릴 수 있어 묶고, 커밋 직전에 changed_at 을 다시 찍는다(AdminAlertWriter 와 같은 까닭)
            transactions.executeWithoutResult(tx -> {
                Object written = AdminAlertWriter.utc(clock.instant());
                List<UUID> ids = jdbc.query(NAMELESS_OF_TARGET, (rs, i) -> UuidBinary.fromBytes(rs.getBytes(1)), kind.name(), target.get(1));
                for (UUID id : ids) {
                    jdbc.update(FILL, lookup.name(), written, UuidBinary.toBytes(id));
                }
                AdminAlertWriter.restamp(jdbc, ids, clock);
            });
            filled++;
        }
        return filled;
    }
}
