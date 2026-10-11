package com.grandis.nova.payment.api;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.payment.support.PaymentFixtures;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /internal/payment-attempts — 결제창을 열 CAPTURE(PENDING)를 만든다. MySQL 위에서 돈다.
 * 결제할 수 있는 대상인지 · 주인인지는 호출자(order · draw)가 이미 확인했다 — 여기서는 대상 · 금액의 모양만 본다.
 */
@PaymentIntegrationTest
@AutoConfigureMockMvc
class PaymentAttemptApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JwtTokenProvider tokens;

    @MockitoBean
    RevocationChecker revocationChecker;

    String user;
    PaymentTarget target;

    @BeforeEach
    void setUp() {
        user = tokens.create("101", Role.USER, UUID.randomUUID(), TokenType.ACCESS);
        target = PaymentFixtures.newOrderTarget();
    }

    @Test
    void opensPendingCaptureForTarget() throws Exception {
        String body = open(target.type().name(), target.id(), "1250000")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.amount").value(1250000))
                .andReturn().getResponse().getContentAsString();

        String providerOrderId = JsonPath.read(body, "$.data.providerOrderId");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT transaction_type, status, amount, provider_order_id, attempt_count FROM payment_transactions
                WHERE target_type = ? AND target_id = ?
                """, target.type().name(), UuidBinary.toBytes(target.id()));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("transaction_type")).isEqualTo("CAPTURE");
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("amount").toString()).isEqualTo("1250000");
            assertThat(row.get("provider_order_id")).isEqualTo(providerOrderId);
            assertThat(row.get("attempt_count")).isEqualTo(0);
        });
    }

    // 준비는 결제창을 다시 열 때마다 새 결제사 주문 번호를 받는다. 버려진 PENDING 은 그대로 남는다.
    @Test
    void eachCallOpensNewPendingCapture() throws Exception {
        String first = providerOrderIdOf(open("ORDER", target.id(), "1000").andExpect(status().isCreated()));
        String second = providerOrderIdOf(open("ORDER", target.id(), "1000").andExpect(status().isCreated()));

        assertThat(second).isNotEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_transactions WHERE target_type = 'ORDER' AND target_id = ? AND status = 'PENDING'
                """, Integer.class, UuidBinary.toBytes(target.id()))).isEqualTo(2);
    }

    @Test
    void acceptsDrawEntryTarget() throws Exception {
        PaymentTarget entry = PaymentFixtures.newDrawEntryTarget();

        open("DRAW_ENTRY", entry.id(), "1000").andExpect(status().isCreated());
    }

    // 금액은 원 단위 정수 표기 · 양수 · 12자리 이하. 0원은 결제창을 열 수 없다.
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1000", "1000.5", "1000.0", "1000000000000"})
    void rejectsInvalidAmount(String amount) throws Exception {
        open("ORDER", target.id(), amount)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(rowsFor(target)).isZero();
    }

    @Test
    void rejectsNonUuidTargetId() throws Exception {
        perform("{\"targetType\":\"ORDER\",\"targetId\":101,\"amount\":1000}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsMissingFields() throws Exception {
        perform("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations.length()").value(3));
    }

    @Test
    void rejectsUnknownTargetType() throws Exception {
        perform("{\"targetType\":\"GIFT\",\"targetId\":\"%s\",\"amount\":1000}".formatted(target.id()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private ResultActions open(String targetType, UUID targetId, String amount) throws Exception {
        return perform("{\"targetType\":\"%s\",\"targetId\":\"%s\",\"amount\":%s}".formatted(targetType, targetId, amount));
    }

    private ResultActions perform(String json) throws Exception {
        return mockMvc.perform(post("/internal/payment-attempts")
                .header(BearerTokens.HEADER, BearerTokens.value(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static String providerOrderIdOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.providerOrderId");
    }

    private int rowsFor(PaymentTarget target) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_transactions WHERE target_type = ? AND target_id = ?
                """, Integer.class, target.type().name(), UuidBinary.toBytes(target.id()));
    }
}
