package com.grandis.nova.payment.refund;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.payment.client.toss.TossPayment;
import com.grandis.nova.payment.vo.PaymentTarget;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 관리자 환불 조치 기록(payment_refund_admin_actions). 조치가 거래 행을 바꾼 트랜잭션 안에서 적는다(MANDATORY) — 조치가 되돌아가면 기록도
 * 없다. 받아들인 조치만이다. 결제 키는 싣지 않는다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class RefundAdminActions {

    /** 표의 action 값. 이름이 곧 저장 값이다(ck_payment_refund_admin_action). */
    enum Kind {
        RETRY, RETRY_AFTER_ESCALATION, MANUAL_COMPLETE
    }

    private static final String SQL = """
            INSERT INTO payment_refund_admin_actions
                (id, target_type, target_id, action, previous_transaction_id, transaction_id, requested_by, note,
                 provider_status, provider_total_amount, provider_balance_amount, created_at)
            VALUES (:id, :targetType, :targetId, :action, :previous, :transaction, :requestedBy, :note,
                    :providerStatus, :total, :balance, :createdAt)
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    RefundAdminActions(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void record(PaymentTarget target, Kind kind, UUID previousTransactionId, UUID transactionId, String requestedBy,
                String note, TossPayment provider, Instant now) {
        jdbcTemplate.update(SQL, new MapSqlParameterSource()
                .addValue("id", UuidBinary.toBytes(UUID.randomUUID()))
                .addValue("targetType", target.type().name())
                .addValue("targetId", UuidBinary.toBytes(target.id()))
                .addValue("action", kind.name())
                .addValue("previous", UuidBinary.toBytes(previousTransactionId))
                .addValue("transaction", UuidBinary.toBytes(transactionId))
                .addValue("requestedBy", requestedBy)
                .addValue("note", note)
                .addValue("providerStatus", provider.status().name())
                .addValue("total", BigDecimal.valueOf(provider.totalAmount()))
                .addValue("balance", BigDecimal.valueOf(provider.balanceAmount()))
                .addValue("createdAt", LocalDateTime.ofInstant(now.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC)));
    }
}
