package com.grandis.nova.member.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 시험 데이터. 회원 행을 SQL 로 바로 넣는다 — 저장소를 모킹한 시험도 같은 방법으로 행을 만들 수 있어야 한다.
 *
 * 매번 새 행을 만들고 지우지 않는다. 유일 칸은 UUID 로 채워 시험끼리 겹치지 않으므로
 * 커밋하는 시험과 롤백하는 시험이 같은 컨테이너를 순서 상관없이 쓸 수 있다.
 */
public class ShopFixtures {

    private final JdbcTemplate jdbc;

    public ShopFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 카카오 로그인을 막 마친 회원. 이름·이메일·연락처와 기본 배송지는 비어 있다. */
    public long customer() {
        return customer("테스트 회원");
    }

    public long customer(String displayName) {
        String kakaoId = "t-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customers (kakao_id, display_name, created_at, updated_at)
                VALUES (?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, kakaoId, displayName);
        return jdbc.queryForObject("SELECT id FROM customers WHERE kakao_id = ?", Long.class, kakaoId);
    }
}
