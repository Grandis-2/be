package com.grandis.nova.preorder.support;

import com.grandis.nova.common.UuidBinary;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/** 다른 커넥션에서 회차 행을 FOR UPDATE 로 쥔다 — 접수가 잠금을 오래 쥔 상황. 닫으면 롤백해 놓는다. */
public final class CampaignRowLock implements AutoCloseable {

    private final Connection connection;

    private CampaignRowLock(Connection connection) {
        this.connection = connection;
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
            return new CampaignRowLock(connection);
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
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
