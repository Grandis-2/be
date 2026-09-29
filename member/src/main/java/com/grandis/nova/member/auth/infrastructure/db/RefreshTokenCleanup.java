package com.grandis.nova.member.auth.infrastructure.db;

import com.grandis.nova.common.security.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 회원 리프레시 행을 주기적으로 지운다. 회전할 때마다 행이 하나 늘고(액세스 30분 · 리프레시 14일이면 쓰는 로그인 하나가 최대 약 672행),
 * 지우는 곳이 없으면 표와 인덱스가 끝없이 커진다.
 *
 * <p><b>만료 즉시가 아니라 액세스 토큰 유효기간만큼 뒤에 지운다</b>(`expires_at <= 지금 − jwt.access-token-validity`). 회전은 "이미 교체된 토큰"
 * 판정을 만료 판정보다 먼저 하고, 재사용이면 체인을 끊으면서 그 세션의 액세스 토큰도 폐기 표식으로 죽인다(TokenService). 만료 즉시 지우면 만료
 * 직전에 나간 액세스 토큰이 재사용 탐지 없이 최대 유효기간만큼 살아남는다(리뷰 실측: 만료 5초 뒤 재사용 — 정리 전 표식 생김, 정리 뒤 안 생김).
 * 유효기간이 지나면 그 체인이 낸 액세스 토큰은 전부 죽었으므로 행을 지워도 잃는 것이 없다. 그래서 보존은 "만료 뒤 액세스 유효기간(기본 30분)"이다.
 *
 * <p>회전은 원 행의 만료 시각을 그대로 물려받으므로(절대 만료) 한 로그인 체인의 행은 같은 시각에 함께 만료되고 함께 지워진다.
 *
 * <p>묶음(batchSize)씩, 한 번에 최대 maxBatchesPerRun 묶음까지. 묶음마다 지울 행의 id 를 잠그지 않고 고른 뒤 그 id 만 기본키로 지운다 —
 * 한 문장 DELETE 는 진행 중인 회전이 잡은 유효 행까지 기다렸다(RefreshTokenRepository#deleteExpired). 여러 인스턴스가 같은 id 를 고르면
 * 먼저 지운 쪽이 지우고 다른 쪽의 기본키 삭제는 0행이 된다(리뷰 실측: 둘이 동시에 3000행 → 1600 + 1400, 예외 0).
 *
 * <p>동시 회전과의 관계: 만료된 체인의 교체된 토큰이 재사용되면 회전이 체인 폐기(`ix_refresh_family` 순서로 잠금)를 하는데, 같은 행을 정리가
 * 기본키 순서로 지우면 교착할 수 있고 회전 쪽이 희생된다(리뷰 실측). 위의 유예 덕에 둘이 겹치는 것은 그 체인의 액세스 토큰이 이미 다 죽은 뒤다.
 * 주기는 `member.refresh-cleanup.cron`(기본 10분마다, "-" 면 끈다).
 */
@Component
public class RefreshTokenCleanup {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanup.class);

    private final RefreshTokenRepository rows;
    private final Clock clock;
    private final Settings settings;
    private final Duration grace;

    public RefreshTokenCleanup(RefreshTokenRepository rows, Clock clock, Settings settings, JwtProperties jwt) {
        this.rows = rows;
        this.clock = clock;
        this.settings = settings;
        this.grace = jwt.accessTokenValidity();
    }

    @Scheduled(cron = "${member.refresh-cleanup.cron:0 */10 * * * *}")
    void scheduled() {
        int deleted = run();
        if (deleted > 0) {
            log.info("expired refresh tokens deleted: {}", deleted);
        }
    }

    /** 한 번 돈다. 지운 행 수. 묶음마다 따로 커밋한다. */
    public int run() {
        // 회전의 만료 판정과 같은 시계 · 같은 정밀도에서 액세스 유효기간만큼 뺀다 — 이 시각 이전에 만료된 체인만 지운다
        Instant cutoff = clock.instant().truncatedTo(ChronoUnit.SECONDS).minus(grace);
        int total = 0;
        for (int batch = 0; batch < settings.maxBatchesPerRun(); batch++) {
            List<Long> expired = rows.findExpiredIds(cutoff, settings.batchSize());   // 잠그지 않고 고른다
            if (expired.isEmpty()) {
                break;
            }
            total += rows.deleteExpired(expired, cutoff);   // 고른 행만 기본키로 — 유효 행은 잠그지도 기다리지도 않는다
            if (expired.size() < settings.batchSize()) {
                break;
            }
        }
        return total;
    }

    /** `member.refresh-cleanup.*`. 0 이하는 기동 실패. */
    @ConfigurationProperties("member.refresh-cleanup")
    public record Settings(@DefaultValue("1000") int batchSize, @DefaultValue("100") int maxBatchesPerRun) {

        public Settings {
            if (batchSize <= 0 || maxBatchesPerRun <= 0) {
                throw new IllegalArgumentException("member.refresh-cleanup.batch-size and max-batches-per-run must be positive");
            }
        }
    }
}
