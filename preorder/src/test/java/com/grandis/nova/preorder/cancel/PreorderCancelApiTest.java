package com.grandis.nova.preorder.cancel;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.integration.order.Cancelability;
import com.grandis.nova.preorder.integration.order.OrderClient;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.AccessTokens;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static com.grandis.nova.preorder.support.AccessTokens.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PreorderIntegrationTest
@AutoConfigureMockMvc
class PreorderCancelApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PreorderAcceptService acceptService;

    @MockitoBean
    CatalogClient catalogClient;

    @MockitoBean
    OrderClient orderClient;

    ShopFixtures fixtures;
    UUID customerId;
    AcceptResult accepted;
    String token;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        customerId = fixtures.customer();
        accepted = new AcceptFixtures(acceptService, fixtures, catalogClient).accept(customerId);
        token = AcceptFixtures.tokenOf(accepted);
    }

    @Test
    void 취소를_시작하면_202_CANCELING_이고_등록_작업_무효화와_주문_정리_요청을_남긴다() throws Exception {
        orderAnswers(true, null);

        String accessToken = AccessTokens.customerToken(customerId);

        mockMvc.perform(post("/api/v1/preorders/{id}/cancel", token).with(AccessTokens.withToken(accessToken)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.preorderId").value(token))
                .andExpect(jsonPath("$.data.status").value("CANCELING"))
                .andExpect(jsonPath("$.data.version").value(2));

        verify(orderClient).getCancelability(accepted.preorder().id());
        assertThat(jobStatus("REGISTER")).isEqualTo("CANCELED");
        Map<String, Object> outbox = jdbcTemplate.queryForMap("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reason')) AS reason,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.preorderId')) AS preorder_id
                  FROM preorder_outbox_events WHERE event_type = 'PREORDER_CANCEL_REQUESTED' AND aggregate_id = ?
                """, (Object) UuidBinary.toBytes(accepted.preorder().id()));
        assertThat(outbox).containsEntry("reason", "USER").containsEntry("preorder_id", token);
    }

    @Test
    void 이미_취소_중이면_order_에_묻지_않고_지금_상태를_돌려준다() throws Exception {
        orderAnswers(true, null);
        cancel(customerId).andExpect(status().isAccepted());

        cancel(customerId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("CANCELING"))
                .andExpect(jsonPath("$.data.version").value(2));

        verify(orderClient).getCancelability(accepted.preorder().id());
        assertThat(fixtures.count("""
                SELECT COUNT(*) FROM preorder_outbox_events WHERE event_type = 'PREORDER_CANCEL_REQUESTED' AND aggregate_id = ?
                """, (Object) UuidBinary.toBytes(accepted.preorder().id()))).isEqualTo(1);
    }

    /** 두 요청이 모두 취소 가능 판정을 받고 시작 트랜잭션에 겹쳐 들어와도 예약 행 잠금으로 한 번만 시작한다. */
    @Test
    void 같은_예약을_동시에_취소해도_취소는_한_번만_시작된다() throws Exception {
        orderAnswers(true, null);

        List<Outcome<String>> outcomes = Concurrently.run(2, i -> () -> cancel(customerId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("CANCELING"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andReturn().getResponse().getContentAsString());

        assertThat(outcomes).allMatch(Outcome::succeeded);
        UUID preorderId = accepted.preorder().id();
        assertThat(fixtures.count("""
                SELECT COUNT(*) FROM preorder_outbox_events WHERE event_type = 'PREORDER_CANCEL_REQUESTED' AND aggregate_id = ?
                """, (Object) UuidBinary.toBytes(preorderId))).isEqualTo(1);
        assertThat(fixtures.count("SELECT COUNT(*) FROM preorder_events WHERE preorder_id = ? AND to_status = 'CANCELING'",
                (Object) UuidBinary.toBytes(preorderId))).isEqualTo(1);
    }

    @Test
    void 배송이_시작됐으면_409_이고_상태는_그대로다() throws Exception {
        orderAnswers(false, "SHIPPED");

        cancel(customerId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_CANCELABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("orderStatus=SHIPPED"));

        assertThat(preorderStatus()).isEqualTo("PENDING_SYNC");
        assertThat(jobStatus("REGISTER")).isEqualTo("PENDING");
    }

    @Test
    void order_가_답하지_않으면_한_번_다시_묻고_503_과_Retry_After_이며_상태는_그대로다() throws Exception {
        given(orderClient.getCancelability(any())).willThrow(new ResourceAccessException("timeout"));

        cancel(customerId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));

        verify(orderClient, times(2)).getCancelability(accepted.preorder().id());
        assertThat(preorderStatus()).isEqualTo("PENDING_SYNC");
    }

    @Test
    void 남의_예약은_존재를_알리지_않고_404_order_에도_묻지_않는다() throws Exception {
        cancel(fixtures.customer())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_FOUND"));

        verify(orderClient, never()).getCancelability(any());
    }

    @Test
    void 관리자_취소는_사유를_ADMIN_이력으로_남긴다() throws Exception {
        orderAnswers(true, null);

        mockMvc.perform(post("/api/v1/admin/preorders/{id}/cancel", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"매크로 의심 접수\"}")
                        .with(admin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("CANCELING"));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT actor, reason FROM preorder_events WHERE preorder_id = ? AND to_status = 'CANCELING'
                """, (Object) UuidBinary.toBytes(accepted.preorder().id())))
                .containsEntry("actor", "ADMIN").containsEntry("reason", "매크로 의심 접수");
    }

    @Test
    void 관리자_사유가_짧으면_400_사용자_토큰으로는_관리자_취소를_못_한다() throws Exception {
        mockMvc.perform(post("/api/v1/admin/preorders/{id}/cancel", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"짧음\"}")
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("reason"));
        mockMvc.perform(post("/api/v1/admin/preorders/{id}/cancel", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"매크로 의심 접수\"}")
                        .with(customer(customerId)))
                .andExpect(status().isForbidden());
    }

    private ResultActions cancel(UUID customer) throws Exception {
        return mockMvc.perform(post("/api/v1/preorders/{id}/cancel", token)
                .with(customer(customer)));
    }

    private void orderAnswers(boolean cancelable, String orderStatus) {
        given(orderClient.getCancelability(accepted.preorder().id())).willReturn(
                ApiResponse.ok(new Cancelability(orderStatus, cancelable, orderStatus)));
    }

    private String preorderStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM preorders WHERE id = ?", String.class,
                (Object) UuidBinary.toBytes(accepted.preorder().id()));
    }

    private String jobStatus(String jobType) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM preorder_sync_jobs WHERE preorder_id = ? AND job_type = ?", String.class,
                UuidBinary.toBytes(accepted.preorder().id()), jobType);
    }
}
