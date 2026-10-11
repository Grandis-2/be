package com.grandis.nova.preorder.query;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static com.grandis.nova.preorder.support.AccessTokens.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PreorderIntegrationTest
@AutoConfigureMockMvc
class AdminPreorderQueryApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PreorderAcceptService acceptService;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    AcceptFixtures accepts;
    UUID customerId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        customerId = fixtures.customer();
    }

    @Test
    void 회원과_상품으로_거르고_오프셋_페이지로_준다() throws Exception {
        AcceptResult mine = accepts.accept(customerId);
        accepts.accept(customerId);
        accepts.accept(fixtures.customer());

        mockMvc.perform(get("/api/v1/admin/preorders").param("customerId", customerId.toString())
                        .param("page", "0").param("size", "1").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.customerId").doesNotExist());
        mockMvc.perform(get("/api/v1/admin/preorders").param("productId", mine.preorder().productId().toString())
                        .with(admin()))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(mine)))
                .andExpect(jsonPath("$.data.items[0].customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.data.items[0].registerJobStatus").value("PENDING"));
    }

    @Test
    void 등록_작업_상태로_재처리_대기_예약을_거른다() throws Exception {
        AcceptResult deadLettered = accepts.accept(customerId);
        accepts.accept(fixtures.customer());
        jdbcTemplate.update("""
                UPDATE preorder_sync_jobs SET status = 'DEAD_LETTER', dead_lettered_at = UTC_TIMESTAMP(6)
                 WHERE preorder_id = ?
                """, (Object) UuidBinary.toBytes(deadLettered.preorder().id()));

        mockMvc.perform(get("/api/v1/admin/preorders").param("registerJobStatus", "DEAD_LETTER")
                        .with(admin()))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(deadLettered)))
                .andExpect(jsonPath("$.data.items[0].registerJobStatus").value("DEAD_LETTER"));
    }

    @Test
    void 조건이_하나도_없어도_조회된다() throws Exception {
        // 이 테스트만의 데이터가 아니라 스키마 전체를 본다. 목록이 최신순이므로 방금 만든 예약이 첫 줄이어야 한다.
        AcceptResult accepted = accepts.accept(customerId);

        mockMvc.perform(get("/api/v1/admin/preorders").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(accepted)))
                .andExpect(jsonPath("$.data.items[0].customerId").value(customerId.toString()));
    }

    @Test
    void 기간으로_거른다() throws Exception {
        accepts.accept(customerId);

        mockMvc.perform(get("/api/v1/admin/preorders").param("customerId", customerId.toString())
                        .param("from", Instant.now().plusSeconds(60).toString())
                        .with(admin()))
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void 상세는_작업과_시도와_이력을_함께_준다() throws Exception {
        AcceptResult accepted = accepts.accept(customerId);
        UUID jobId = jdbcTemplate.queryForObject("SELECT id FROM preorder_sync_jobs WHERE preorder_id = ?",
                (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes(1)),
                        (Object) UuidBinary.toBytes(accepted.preorder().id()));
        fixtures.syncAttempt(jobId, 1, "TRANSIENT_FAILURE", 503, "UPSTREAM_UNAVAILABLE");
        fixtures.syncAttempt(jobId, 2, null, null, null);

        mockMvc.perform(get("/api/v1/admin/preorders/" + AcceptFixtures.tokenOf(accepted)).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.data.admissionTicketId").exists())
                .andExpect(jsonPath("$.data.syncJobs", hasSize(1)))
                .andExpect(jsonPath("$.data.syncJobs[0].jobType").value("REGISTER"))
                .andExpect(jsonPath("$.data.syncJobs[0].attemptCount").value(2))
                .andExpect(jsonPath("$.data.syncJobs[0].lastErrorCode").doesNotExist())
                .andExpect(jsonPath("$.data.syncJobs[0].attempts[0].httpStatus").value(503))
                .andExpect(jsonPath("$.data.syncJobs[0].attempts[1].result").doesNotExist())
                .andExpect(jsonPath("$.data.events", hasSize(1)))
                .andExpect(jsonPath("$.data.cancelable").value(true));
    }

    @Test
    void 내부_메모를_바꾸고_이력은_남기지_않는다() throws Exception {
        AcceptResult accepted = accepts.accept(customerId);

        mockMvc.perform(patch("/api/v1/admin/preorders/" + AcceptFixtures.tokenOf(accepted))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"internalNote\":\"VIP 고객\"}")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.internalNote").value("VIP 고객"))
                .andExpect(jsonPath("$.data.events", hasSize(1)));
        assertThat(jdbcTemplate.queryForObject("SELECT internal_note FROM preorders WHERE preorder_token = ?",
                String.class, AcceptFixtures.tokenOf(accepted))).isEqualTo("VIP 고객");
    }

    @Test
    void 메모_외의_필드를_바꾸려_하면_409() throws Exception {
        AcceptResult accepted = accepts.accept(customerId);

        mockMvc.perform(patch("/api/v1/admin/preorders/" + AcceptFixtures.tokenOf(accepted))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"internalNote\":\"메모\",\"optionId\":9}")
                        .with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PREORDER_FIELD_IMMUTABLE"))
                .andExpect(jsonPath("$.error.details.fields[0]").value("optionId"));
    }

    @Test
    void 없는_예약은_404_사용자_토큰은_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/preorders/" + ShopFixtures.unique())
                        .with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/admin/preorders").with(customer(customerId)))
                .andExpect(status().isForbidden());
    }

}
