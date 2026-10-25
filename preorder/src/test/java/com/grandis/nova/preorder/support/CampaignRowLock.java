package com.grandis.nova.preorder.support;

import com.grandis.nova.common.UuidBinary;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 다른 커넥션에서 회차 행을 FOR UPDATE 로 쥔다 — 접수가 잠금을 오래 쥔 상황. 닫으면 롤백해 놓는다. */
public final class CampaignRowLock implements AutoCloseable {

    private final Connection connection;
    private final UUID productId;

    private CampaignRowLock(Connection connection, UUID productId) {
        this.connection = connection;
        this.productId = productId;
    }

    public static CampaignRowLock hold(DataSource dataSource, UUID productId) throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement(
                    "SELECT product_id FROM preorder_campaigns WHERE product_id = ? FOR UPDATE")) {
                lock.setBytes(1, UuidBinary.toBytes(productId));
                lock.executeQuery().close();
            }
            return new CampaignRowLock(connection, productId);
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    /** 쥔 채 마감 시각을 바꾸고 커밋해 잠금을 푼다 — 접수가 잠금을 기다리는 동안 회차를 닫은 관리자 트랜잭션. */
    public void commitClosing(Instant closesAt) throws SQLException {
        try (PreparedStatement close = connection.prepareStatement(
                "UPDATE preorder_campaigns SET closes_at = ? WHERE product_id = ?")) {
            close.setObject(1, LocalDateTime.ofInstant(closesAt.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
            close.setBytes(2, UuidBinary.toBytes(productId));
            close.executeUpdate();
        }
        connection.commit();
    }

    @Override
    public void close() throws SQLException {
        try {
            connection.rollback();
        } finally {
            connection.close();
        }
    }
}
