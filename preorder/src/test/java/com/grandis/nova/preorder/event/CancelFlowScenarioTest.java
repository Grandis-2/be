package com.grandis.nova.preorder.event;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.cancel.application.PreorderCancelService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.integration.order.Cancelability;
import com.grandis.nova.preorder.integration.order.OrderClient;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 흐름 ②(예약 취소) 끝까지(계획서 P5-5). order · worker 는 대역이고 그 결과를 이벤트 처리기에 직접 넣는다.
 *
 * 주문 쪽 네 갈래 — 주문 없음 · 미결제 · 결제 · 배송 시작 — 는 preorder 에게 세 가지 결과로 온다.
 * 미결제와 결제(환불 뒤)는 둘 다 CANCELED 로 오므로 preorder 의 처리가 같다.
 */
@PreorderIntegrationTest
class CancelFlowScenarioTest {

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    PreorderCancelService cancelService;

    @Autowired
    PreorderEventHandler handler;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    @MockitoBean
    OrderClient orderClient;

    ShopFixtures fixtures;
    Long customerId;
    Long preorderId;
    String token;
    /** 외부 예약 번호는 UNIQUE 라 테스트마다 새로 만든다(DB 를 테스트끼리 공유한다). */
    String externalNumber;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        customerId = fixtures.customer();
        AcceptResult accepted = new AcceptFixtures(acceptService, fixtures, catalogClient).accept(customerId);
        preorderId = accepted.preorder().id();
        token = AcceptFixtures.tokenOf(accepted);
        given(orderClient.getCancelability(any(), any()))
                .willReturn(ApiResponse.ok(new Cancelability(null, true, null)));
        externalNumber = "R-" + ShopFixtures.unique();
        handler.onExternalJobSucceeded(new ExternalJobSucceeded(fixtures.workerSucceeds(preorderId, "REGISTER"),
                token, "REGISTER", externalNumber));
    }

    /** NO_ORDER = 주문 없음, CANCELED = 미결제 주문 취소 또는 결제 주문 환불 완료. */
    @ParameterizedTest(name = "주문 정리 결과 {0}")
    @EnumSource(value = PreorderOrderSettled.Result.class, names = {"NO_ORDER", "CANCELED"})
    void 주문이_정리되면_외부_취소를_거쳐_취소_완료가_된다(PreorderOrderSettled.Result result) {
        cancelService.cancelByCustomer(customerId, token, null, null);
        handler.onOrderSettled(new PreorderOrderSettled(token, result, null, fixtures.cancelSequence(preorderId)));
        Long cancelJobId = fixtures.workerSucceeds(preorderId, "CANCEL");
        handler.onExternalJobSucceeded(new ExternalJobSucceeded(cancelJobId, token, "CANCEL", null));

        assertThat(history()).containsExactly(
                "null>PENDING_SYNC", "PENDING_SYNC>PAYABLE", "PAYABLE>CANCELING", "CANCELING>CANCELED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(request_payload, '$.reservationNo')) FROM preorder_sync_jobs"
                        + " WHERE id = ?", String.class, cancelJobId)).isEqualTo(externalNumber);
    }

    @Test
    void 확인_뒤에_배송이_시작되면_거절되어_결제_가능으로_돌아가고_외부_취소는_없다() {
        cancelService.cancelByCustomer(customerId, token, null, null);
        handler.onOrderSettled(new PreorderOrderSettled(token, PreorderOrderSettled.Result.REJECTED, "SHIPPED",
                fixtures.cancelSequence(preorderId)));

        assertThat(history()).containsExactly(
                "null>PENDING_SYNC", "PENDING_SYNC>PAYABLE", "PAYABLE>CANCELING", "CANCELING>PAYABLE");
        assertThat(fixtures.count(
                "SELECT COUNT(*) FROM preorder_sync_jobs WHERE preorder_id = ? AND job_type = 'CANCEL'", preorderId))
                .isZero();
    }

    private List<String> history() {
        return jdbcTemplate.query("""
                SELECT from_status, to_status FROM preorder_events WHERE preorder_id = ? ORDER BY event_sequence
                """, (rs, n) -> rs.getString(1) + ">" + rs.getString(2), preorderId);
    }
}
