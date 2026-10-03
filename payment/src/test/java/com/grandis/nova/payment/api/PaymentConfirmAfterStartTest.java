package com.grandis.nova.payment.api;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.payment.PaymentErrorCode;
import com.grandis.nova.payment.client.toss.TossCommandResult;
import com.grandis.nova.payment.client.toss.TossPaymentClient;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.prepare.PreparePaymentService;
import com.grandis.nova.payment.support.PaymentFixtures;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계약: 거래를 시작한 뒤에는 4xx 를 내지 않는다. 호출자는 4xx 일부를 "시작하지 않았다" 로 읽어 대상을 결제 전으로 되돌리므로,
 * 시작한 뒤 업무 오류가 4xx 로 나가면 돈이 나간 채 미결제가 된다. 결과 반영 중 업무 오류를 주입해 5xx 인지 본다.
 */
@PaymentIntegrationTest
@AutoConfigureMockMvc
class PaymentConfirmAfterStartTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    JwtTokenProvider tokens;

    @Autowired
    PreparePaymentService prepareService;

    @MockitoBean
    RevocationChecker revocationChecker;

    @MockitoBean
    TossPaymentClient toss;

    @MockitoBean
    OutboxWriter outbox;

    @Test
    void businessErrorAfterStartIsServerError() throws Exception {
        PaymentTarget target = PaymentFixtures.newOrderTarget();
        PaymentTransaction opened = prepareService.open(target, Money.won(5000));
        given(toss.confirm(any(), any())).willReturn(new TossCommandResult.Rejected("REJECT_CARD_PAYMENT", "거절"));
        given(outbox.append(any())).willThrow(new BusinessException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
        String user = tokens.create("101", Role.USER, UUID.randomUUID(), TokenType.ACCESS);

        mockMvc.perform(post("/internal/payment-attempts/{id}/confirm", opened.providerOrderId().value())
                        .header(BearerTokens.HEADER, BearerTokens.value(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("targetType", "ORDER", "targetId", target.id(),
                                "paymentKey", PaymentFixtures.newProviderPayment().value(), "amount", 5000,
                                "startAllowed", true))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

        // 결과 반영은 통째로 되돌아가 리스를 쥔 채 남는다 — 복구(NV-102)가 이어 받는다
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_transactions WHERE id = ?", String.class,
                opened.id())).isEqualTo("PROCESSING");
    }
}
