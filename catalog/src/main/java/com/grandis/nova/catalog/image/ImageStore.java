package com.grandis.nova.catalog.image;

import com.grandis.nova.common.UuidBinary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 이미지 표(images). 행은 업로드가 UNUSED 로 넣고, 상품 · 홍보 저장이 붙이고(USED) 뗀다(UNUSED). 지우기는 정리 배치(batch)가 한다.
 * 시각은 앱 시계의 UTC 로 넣는다 — 배치가 같은 기준으로 기준 시각을 계산한다.
 * 잠금 · 갱신은 전부 id(PRIMARY)로 건다 — object_key 로 걸면 id 로 지우는 배치와 다른 인덱스 순서로 잠가 교착한다(MySQL 8.4.11 실측).
 */
@Repository
public class ImageStore {

    public enum Status { UNUSED, USED }

    /** 잠근 행. */
    public record Row(UUID id, String objectKey, Status status) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ImageStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertUnused(UUID id, String objectKey, Instant now) {
        jdbc.update("INSERT INTO images (id, object_key, status, created_at, updated_at) VALUES (:id, :key, 'UNUSED', :now, :now)",
                new MapSqlParameterSource().addValue("id", UuidBinary.toBytes(id)).addValue("key", objectKey).addValue("now", utc(now)));
    }

    /**
     * ids 의 행을 한 문장으로 잠근다(FOR UPDATE). 없는 id 는 빠진다. 붙일 것과 뗄 것을 합쳐 한 번에 잠근다 — 두 문장으로 나누면 사진을 맞바꾸는
     * 두 저장이 서로의 둘째 잠금을 기다려 교착한다.
     */
    public Map<UUID, Row> lock(Collection<UUID> ids) {
        Map<UUID, Row> rows = new HashMap<>();
        if (ids.isEmpty()) {
            return rows;
        }
        jdbc.query("SELECT id, object_key, status FROM images WHERE id IN (:ids) FOR UPDATE",
                new MapSqlParameterSource("ids", ids.stream().map(UuidBinary::toBytes).toList()),
                rs -> {
                    UUID id = UuidBinary.fromBytes(rs.getBytes("id"));
                    rows.put(id, new Row(id, rs.getString("object_key"), Status.valueOf(rs.getString("status"))));
                });
        return rows;
    }

    /** ids 의 상태를 바꾸고 updated_at 을 now 로 찍는다. 잠근 뒤에 부른다. */
    public void mark(Collection<UUID> ids, Status status, Instant now) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("UPDATE images SET status = :status, updated_at = :now WHERE id IN (:ids)",
                new MapSqlParameterSource().addValue("status", status.name()).addValue("now", utc(now))
                        .addValue("ids", ids.stream().map(UuidBinary::toBytes).toList()));
    }

    /** 연결 시간대(serverTimezone=UTC)와 상관없이 UTC 벽시계 값으로 넣는다. */
    private static LocalDateTime utc(Instant now) {
        return LocalDateTime.ofInstant(now, ZoneOffset.UTC);
    }
}
