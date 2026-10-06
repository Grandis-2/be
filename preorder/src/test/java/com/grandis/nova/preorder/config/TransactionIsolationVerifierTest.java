package com.grandis.nova.preorder.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionIsolationVerifierTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final TransactionIsolationVerifier verifier = new TransactionIsolationVerifier(jdbcTemplate);

    @Test
    void READ_COMMITTED_면_통과() {
        when(jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class)).thenReturn("READ-COMMITTED");

        assertThatCode(() -> verifier.run(null)).doesNotThrowAnyException();
    }

    @Test
    void MySQL_기본값_REPEATABLE_READ_면_기동을_멈춘다() {
        when(jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class)).thenReturn("REPEATABLE-READ");

        assertThatThrownBy(() -> verifier.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REPEATABLE-READ")
                .hasMessageContaining("transaction-isolation");
    }
}
