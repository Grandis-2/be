package com.grandis.nova.catalog.option;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 글 묶음 안에 DB 콜레이션(utf8mb4_0900_ai_ci)이 같다고 보는 둘이 있는가 — 옵션 값 · 상세 영역 이름의 중복 최종 판정. 값이 표의 UNIQUE 에서
 * JSON 문서로 옮겨 오며 DB 가 막던 것을 같은 콜레이션의 질의로 그대로 잰다.
 * 앱의 {@code ProductRegistrationValidator#collationKey} 가 대부분을 정확한 칸 이름으로 먼저 거르고, 그 근사가 못 잡는 확장 문자(ß=ss · Æ=AE ·
 * Œ=OE)를 여기서 잡는다(MySQL 8.4.11 실측 — 지금까지의 UNIQUE 와 같은 결과: 대소문자 · 악센트 · 전각 · 확장은 같고, 공백 차이는 다르다).
 */
@Component
public class CollationDuplicates {

    /** 아래 질의의 VARCHAR(60) 과 같다. */
    static final int MAX_LENGTH = 60;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String QUERY = """
            SELECT COUNT(*) - COUNT(DISTINCT v)
              FROM JSON_TABLE(?, '$[*]' COLUMNS (v VARCHAR(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci PATH '$')) t
            """;

    private final JdbcTemplate jdbc;

    public CollationDuplicates(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param texts 60자 이하(값 · 영역 이름 길이 상한). 넘는 글은 질의가 잘라 비교해 거짓 중복이 되므로 받지 않는다 — 호출자가 길이를 먼저 잰다 */
    public boolean any(List<String> texts) {
        if (texts.stream().anyMatch(text -> text.length() > MAX_LENGTH)) {
            throw new IllegalArgumentException("texts longer than " + MAX_LENGTH + " must be rejected before the collation check");
        }
        if (texts.size() < 2) {
            return false;
        }
        Integer duplicates = jdbc.queryForObject(QUERY, Integer.class, JSON.writeValueAsString(texts));
        return duplicates != null && duplicates > 0;
    }
}
