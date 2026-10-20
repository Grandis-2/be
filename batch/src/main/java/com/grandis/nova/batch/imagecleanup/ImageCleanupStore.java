package com.grandis.nova.batch.imagecleanup;

import com.grandis.nova.common.UuidBinary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 사진 정리가 읽고 쓰는 SQL. images 는 catalog 소유다 — batch 는 기준 시간이 지난 UNUSED 행을 id 로 지우기만 한다. catalog 의 붙이기 ·
 * 떼기도 id 로 잠근다 — object_key 로 지우면 서로 다른 인덱스 순서로 잠가 교착한다(catalog 실측).
 * 시각은 앱 시계의 UTC 로 저장돼 있어 LocalDateTime 으로 넘기고 받는다(드라이버의 시간대 변환을 거치지 않게).
 */
@Repository
class ImageCleanupStore {

    // 사진 주소를 복사해 두고 바꾸지 않는 곳 — 이 주소가 가리키는 사진은 UNUSED 여도 지우지 않는다.
    // 드로우 회차는 만들 때 증정품 사진을(예정 · 진행 · 마감 모두 화면에 보인다), 주문 줄은 주문 때 옵션 사진을(지난 주문 상세) 남긴다.
    // 새로 생기면 catalog 가 알리고 여기에 더한다.
    // 주소 전체가 아니라 객체 키로 비교한다 — CDN 도메인이 바뀌어도 옛 주소를 놓치지 않는다. 서버가 만든 키에는 LIKE 와일드카드(% · _)가
    // 없고, 있더라도 더 많은 행과 맞아 지우지 않는 쪽으로 틀린다. 주소 뒤의 쿼리 · 조각(? · #)은 떼고 비교한다 — catalog 는 새로 붙이는
    // 사진 주소에 쿼리 · 조각을 받지 않지만, 붙어 있어도 같은 객체라 지우지 않는 쪽으로 본다(catalog 의 키 구하기도 URI 경로로 한다).
    // 끝부분 비교라 인덱스를 못 타고 대상 한 장마다 두 테이블을 훑는다 — 하루 한 번 · 대상이 적어 받아들인다. 늘어나는지는 요약 로그의
    // 걸린 시간으로 보고, 커지면 스냅샷 테이블에 객체 키 컬럼과 인덱스를 두는 것을 그 테이블 담당과 정한다
    private static final List<String> SNAPSHOTS = List.of("draw_campaigns", "order_items");

    private static final String REFERENCED = SNAPSHOTS.stream()
            .map(table -> "EXISTS (SELECT 1 FROM " + table + " s WHERE SUBSTRING_INDEX(SUBSTRING_INDEX(s.image_url_snapshot, '#', 1), '?', 1)"
                    + " LIKE CONCAT('%/', i.object_key))")
            .collect(Collectors.joining(" OR ", "(", ")"));

    // (status, updated_at) 인덱스를 탄다. InnoDB 보조 인덱스 끝에 기본 키가 붙어 (updated_at, id) 순서도 인덱스 순서다
    private static final String CANDIDATES = """
            SELECT i.id, i.object_key, i.updated_at, %s AS referenced
              FROM images i
             WHERE i.status = 'UNUSED' AND i.updated_at < :cutoff
            """.formatted(REFERENCED);

    private static final String AFTER = " AND (i.updated_at > :afterAt OR (i.updated_at = :afterAt AND i.id > :afterId))";

    private final JdbcClient jdbc;

    ImageCleanupStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 기준 시각 전에 UNUSED 가 된 사진을 (updated_at, id) 순서로 after 다음부터 읽는다. 못 지운 행(드로우 · 주문이 가리킴 · 그 사이 바뀜)이
     * 남아도 다시 고르지 않는다.
     *
     * @param after 앞 청크의 마지막 사진. null 이면 처음부터
     */
    List<Candidate> candidates(Instant cutoff, Position after, int limit) {
        String sql = CANDIDATES + (after == null ? "" : AFTER) + " ORDER BY i.updated_at, i.id LIMIT :limit";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("cutoff", utc(cutoff)).param("limit", limit);
        if (after != null) {
            statement = statement.param("afterAt", utc(after.updatedAt())).param("afterId", UuidBinary.toBytes(after.id()));
        }
        return statement.query(ImageCleanupStore::candidate).list();
    }

    /**
     * 고를 때의 조건을 한 문장에 다시 걸어 id 로 지운다. 고른 뒤 catalog 가 붙였거나(USED) 다시 뗐거나(updated_at 이 새것) 드로우 ·
     * 주문이 가리키게 됐으면 0행이다.
     *
     * @return 지웠으면 true
     */
    boolean deleteIfStillUnused(UUID id, Instant cutoff) {
        return jdbc.sql("""
                        DELETE FROM images i
                         WHERE i.id = :id
                           AND i.status = 'UNUSED'
                           AND i.updated_at < :cutoff
                           AND NOT %s
                        """.formatted(REFERENCED))
                .param("id", UuidBinary.toBytes(id))
                .param("cutoff", utc(cutoff))
                .update() == 1;
    }

    /** 행 삭제와 같은 트랜잭션에서 부른다 — 커밋되면 S3 객체를 지울 일이 남는다 */
    void addPending(String objectKey, Instant now) {
        jdbc.sql("INSERT INTO image_deletions (object_key, created_at) VALUES (:key, :now)")
                .param("key", objectKey)
                .param("now", utc(now))
                .update();
    }

    void removePending(String objectKey) {
        jdbc.sql("DELETE FROM image_deletions WHERE object_key = :key").param("key", objectKey).update();
    }

    /** 삭제 대기를 키 순서로 after 다음부터 읽는다. 처음은 빈 문자열(utf8mb4_bin 에서 가장 앞)이다 */
    List<String> pending(String after, int limit) {
        return jdbc.sql("SELECT object_key FROM image_deletions WHERE object_key > :after ORDER BY object_key LIMIT :limit")
                .param("after", after)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    long pendingCount() {
        return jdbc.sql("SELECT COUNT(*) FROM image_deletions").query(Long.class).single();
    }

    /** 삭제 대기 중 before 보다 먼저 넣은 행 수와 그중 가장 오래된 시각. 없으면 0건 · null */
    StalePending stalePending(Instant before) {
        return jdbc.sql("SELECT COUNT(*) AS n, MIN(created_at) AS oldest FROM image_deletions WHERE created_at < :before")
                .param("before", utc(before))
                .query((rs, row) -> {
                    LocalDateTime oldest = rs.getObject("oldest", LocalDateTime.class);
                    return new StalePending(rs.getLong("n"), oldest == null ? null : oldest.toInstant(ZoneOffset.UTC));
                })
                .single();
    }

    private static Candidate candidate(ResultSet rs, int row) throws SQLException {
        return new Candidate(UuidBinary.fromBytes(rs.getBytes("id")), rs.getString("object_key"),
                rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getBoolean("referenced"));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 기준 시간이 지난 UNUSED 사진. referenced 면 드로우 회차 · 주문 줄이 그 키를 가리켜 지우지 않는다 */
    record Candidate(UUID id, String objectKey, Instant updatedAt, boolean referenced) {

        Position position() {
            return new Position(updatedAt, id);
        }
    }

    /** 이어 읽는 자리. id 는 같은 시각의 사진(한 저장이 뗀 여러 장은 updated_at 이 같다) 사이의 순서다 */
    record Position(Instant updatedAt, UUID id) {
    }

    /** 오래 남은 삭제 대기 */
    record StalePending(long count, Instant oldest) {
    }
}
