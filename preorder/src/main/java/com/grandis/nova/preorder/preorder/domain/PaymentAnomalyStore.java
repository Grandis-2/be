package com.grandis.nova.preorder.preorder.domain;

import com.grandis.nova.common.UuidBinary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 취소가 끝난 예약에 온 결제 확인(preorder_payment_anomalies). 같은 결제(예약, 결제 시각)는 PK 에 걸려 한 행뿐이다.
 * 중복은 엔티티 없이 SQL 로 가른다 — JPA 저장의 키 충돌은 트랜잭션을 롤백 전용으로 만든다.
 */
@Component
public class PaymentAnomalyStore {

    static final int ORDER_ID_LENGTH = 64;

    private static final String INSERT = """
            INSERT INTO preorder_payment_anomalies (preorder_id, paid_at, order_id, detected_at) VALUES (?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    PaymentAnomalyStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** @return 새로 남겼으면 true, 이미 남긴 결제면 false */
    public boolean record(UUID preorderId, Instant paidAt, String orderId, Instant now) {
        try {
            jdbcTemplate.update(INSERT, UuidBinary.toBytes(preorderId), utc(paidAt), cut(orderId), utc(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 칸 길이(글자 수)에 맞춘다. 외부가 준 값이라 길이를 믿지 않는다 — 넘쳐 INSERT 가 실패하면 메시지가 끝없이 다시 온다. */
    private static String cut(String value) {
        if (value == null || value.codePointCount(0, value.length()) <= ORDER_ID_LENGTH) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, ORDER_ID_LENGTH));
    }

    /** DB 는 UTC 벽시계 시각을 담는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
