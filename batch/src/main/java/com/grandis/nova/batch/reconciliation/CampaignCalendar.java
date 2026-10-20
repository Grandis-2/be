package com.grandis.nova.batch.reconciliation;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 회차 일정(preorder_campaigns)으로 실행 시점을 가린다. batch 는 모든 표를 읽을 수 있다(읽기만).
 * 시각은 DB 에 UTC 로 저장돼 있어 LocalDateTime(UTC)으로 주고받는다 — 드라이버의 시간대 변환을 거치지 않게.
 */
@Component
public class CampaignCalendar {

    private final JdbcClient jdbc;

    CampaignCalendar(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 오픈한 지 window 가 안 된 회차가 있는가 — 신청이 몰리는 구간이라 정기 · 마감 뒤 실행을 건너뛴다 */
    public boolean inIntakeRush(Instant now, Duration window) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM preorder_campaigns WHERE opens_at <= :now AND opens_at > :since)
                        """)
                .param("now", utc(now))
                .param("since", utc(now.minus(window)))
                .query(Boolean.class)
                .single());
    }

    /**
     * 마감 뒤 실행을 돌릴 때가 된 가장 최근 시점(마감 + margin). 그 시점이 lookback 보다 오래됐으면 없다고 본다 —
     * 처음 배포할 때 옛 회차마다 돌지 않게.
     */
    public Optional<Instant> latestCloseReadyAt(Instant now, Duration margin, Duration lookback) {
        Instant readyBefore = now.minus(margin);
        // MAX 는 회차가 없어도 NULL 한 행을 돌려준다
        return jdbc.sql("""
                        SELECT MAX(closes_at) FROM preorder_campaigns WHERE closes_at <= :readyBefore AND closes_at > :since
                        """)
                .param("readyBefore", utc(readyBefore))
                .param("since", utc(readyBefore.minus(lookback)))
                .query(LocalDateTime.class)
                .list().stream()
                .filter(Objects::nonNull)
                .findFirst()
                .map(close -> close.toInstant(ZoneOffset.UTC).plus(margin));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
