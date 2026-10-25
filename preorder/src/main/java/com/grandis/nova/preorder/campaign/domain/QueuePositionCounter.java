package com.grandis.nova.preorder.campaign.domain;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.campaign.ProductStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 회차 순번 카운터. 조건부 UPDATE 한 문장이 회차 행을 잠그고 올리며, 발급한 값은 LAST_INSERT_ID 생성 키로 받는다.
 * 롤백하면 카운터도 되돌아가 빈틈이 없다.
 */
@Component
public class QueuePositionCounter {

    /** 판정은 접수 판정({@code CampaignForAccept})과 같다 — 공개, 판매 중지 아님(판매 상태를 아직 모르면 막지 않는다), 접수 기간 안. */
    private static final String ISSUE = """
            UPDATE preorder_campaigns
               SET next_queue_position = LAST_INSERT_ID(next_queue_position) + 1, updated_at = ?
             WHERE product_id = ? AND opens_at <= ? AND closes_at > ?
               AND visible = 1 AND (product_status IS NULL OR product_status <> ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    QueuePositionCounter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 접수 중인 회차면 순번 하나를 발급한다. 아니면(없음 · 비공개 · 판매 중지 · 기간 밖) 비어 있고 행을 바꾸지 않는다. */
    public OptionalLong issue(UUID productId, Instant now) {
        LocalDateTime at = utc(now);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        int updated = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(ISSUE, Statement.RETURN_GENERATED_KEYS);
            statement.setObject(1, at);
            statement.setBytes(2, UuidBinary.toBytes(productId));
            statement.setObject(3, at);
            statement.setObject(4, at);
            statement.setString(5, ProductStatus.PAUSED.name());
            return statement;
        }, keys);
        if (updated == 0) {
            return OptionalLong.empty();
        }
        Number position = keys.getKey();
        if (position == null) {
            throw new IllegalStateException("순번을 올렸는데 발급한 값을 받지 못했다: productId=" + productId);
        }
        return OptionalLong.of(position.longValue());
    }

    /** DB 는 UTC 벽시계 시각을 담는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
