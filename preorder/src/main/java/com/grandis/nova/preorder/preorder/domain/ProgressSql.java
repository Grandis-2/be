package com.grandis.nova.preorder.preorder.domain;

import com.grandis.nova.preorder.preorder.AdminPreorderSearch;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** 진행 조건을 SQL 로 푼다(preorders 의 별칭은 p). */
final class ProgressSql {

    private ProgressSql() {
    }

    /** 진행 조건. 값이 없는 칸은 걸지 않는다. */
    static List<String> progress(AdminPreorderSearch.Progress progress, MapSqlParameterSource params, String key) {
        List<String> conditions = new ArrayList<>();
        if (progress.registerAttempted() != null) {
            conditions.add((progress.registerAttempted() ? "" : "NOT ") + """
                    EXISTS (SELECT 1 FROM preorder_sync_jobs j
                             WHERE j.preorder_id = p.id AND j.job_type = 'REGISTER' AND j.status <> 'PENDING')""");
        }
        if (progress.payableAfter() != null) {
            conditions.add("p.payable_from > :payableAfter" + key);
            params.addValue("payableAfter" + key, utc(progress.payableAfter()));
        }
        if (progress.payableUntil() != null) {
            conditions.add("p.payable_from <= :payableUntil" + key);
            params.addValue("payableUntil" + key, utc(progress.payableUntil()));
        }
        if (progress.paymentStarted() != null) {
            conditions.add("p.payment_started_at IS " + (progress.paymentStarted() ? "NOT NULL" : "NULL"));
        }
        return conditions;
    }

    /** DB 는 UTC 벽시계 시각을 담는다. Timestamp 로 주고받으면 JVM 시간대만큼 어긋난다. */
    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
