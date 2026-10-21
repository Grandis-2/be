package com.grandis.nova.preorder.accept.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.admission.AdmissionTicketVerifier;
import com.grandis.nova.preorder.campaign.CachedCampaign;
import com.grandis.nova.preorder.campaign.CampaignSchedule;
import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.campaign.ProductStatus;
import com.grandis.nova.preorder.integration.catalog.CatalogReader;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.Preorders;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * UNIQUE · FK 충돌을 계약의 오류로 바꾸는 분기. 동시성 테스트는 경합이 실제로 일어났는지 보장하지 못하므로
 * (차례로 들어오면 재전송 확인이 먼저 잡는다) 분기 자체는 여기서 충돌을 직접 만들어 확인한다.
 */
class PreorderAcceptServiceTest {

    static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-7000-8000-000000001024");
    static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-7000-8000-000000000101");
    static final UUID OPTION_ID = UUID.fromString("00000000-0000-7000-8000-000000001002");

    PreorderAcceptTransaction transaction;
    Preorders preorders;
    Campaigns campaigns;
    PreorderAcceptService service;

    @BeforeEach
    void setUp() {
        transaction = mock(PreorderAcceptTransaction.class);
        preorders = mock(Preorders.class);
        CatalogReader catalogReader = mock(CatalogReader.class);
        given(catalogReader.findProduct(PRODUCT_ID)).willReturn(Optional.empty());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AcceptBulkhead bulkhead = new AcceptBulkhead(new AcceptBulkheadProperties(2, 0.5, 0, Duration.ofSeconds(3),
                Duration.ofSeconds(1), Duration.ofSeconds(3)), 10, registry,
                productId -> AcceptProductTags.OTHER);
        // 회차 캐시가 접수 중이라 하면 잠금 전 판정 없이 접수 트랜잭션으로 간다
        campaigns = mock(Campaigns.class);
        Instant now = Instant.now();
        given(campaigns.findCached(PRODUCT_ID)).willReturn(Optional.of(new CachedCampaign(
                new CampaignSchedule(PRODUCT_ID, now.minusSeconds(3600), now.plusSeconds(3600)), true,
                ProductStatus.ACTIVE, List.of())));
        service = new PreorderAcceptService(mock(AdmissionTicketVerifier.class), catalogReader, transaction, bulkhead,
                campaigns, preorders, new AcceptProductTags(campaigns, registry, Clock.systemUTC()), registry,
                Clock.systemUTC());
    }

    @Test
    void 접수_키_충돌이면_기존_예약을_다시_확인해_결과를_따른다() {
        violates("uq_preorder_idempotency");
        given(transaction.findReplay(any())).willThrow(new BusinessException(PreorderErrorCode.KEY_PAYLOAD_MISMATCH));

        assertThatThrownBy(this::acceptByAdmin)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(PreorderErrorCode.KEY_PAYLOAD_MISMATCH);
    }

    @Test
    void 접수_키_충돌인데_다시_봐도_없으면_원래_예외를_올린다() {
        DataIntegrityViolationException violation = violates("uq_preorder_idempotency");
        given(transaction.findReplay(any())).willReturn(Optional.empty());

        assertThatThrownBy(this::acceptByAdmin).isSameAs(violation);
    }

    @Test
    void 활성_예약_충돌이면_409_와_기존_예약_ID() {
        violates("preorders.uq_preorder_active");
        PreorderSnapshot existing = mock(PreorderSnapshot.class);
        given(existing.preorderToken()).willReturn("9f1c2d3e");
        given(preorders.findActive(CUSTOMER_ID, PRODUCT_ID)).willReturn(Optional.of(existing));

        assertThatThrownBy(this::acceptByAdmin)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(PreorderErrorCode.ACTIVE_PREORDER_EXISTS);
                    assertThat(e.details()).containsEntry("existingPreorderId", "9f1c2d3e");
                });
    }

    @Test
    void 입장권_충돌이면_409_ADMISSION_TICKET_USED() {
        violates("uq_preorder_admission");

        assertThatThrownBy(this::acceptByAdmin)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(PreorderErrorCode.ADMISSION_TICKET_USED);
    }

    @Test
    void 모르는_제약이면_삼키지_않고_그대로_올린다() {
        DataIntegrityViolationException violation = violates("uq_something_else");

        assertThatThrownBy(this::acceptByAdmin).isSameAs(violation);
    }

    /** 캐시에서 고른 차수가 그사이 교체됐으면 캐시를 비우고 한 번만 다시 접수한다 — 다시는 DB 에서 고른다. */
    @Test
    void 차수_외래_키에_막히면_캐시를_비우고_한_번만_다시_접수한다() {
        DataIntegrityViolationException violation = violation("fk_preorder_batch");
        AcceptResult accepted = mock(AcceptResult.class);
        given(transaction.accept(any(), any())).willThrow(violation).willReturn(accepted);

        assertThat(service.acceptByAdmin(CUSTOMER_ID, PRODUCT_ID, OPTION_ID, "key-00000001", "전화 접수", null))
                .isSameAs(accepted);

        verify(campaigns).evictCached(PRODUCT_ID);
        verify(transaction, times(2)).accept(any(), any());
    }

    @Test
    void 다시_해도_차수_외래_키에_막히면_그대로_올린다() {
        DataIntegrityViolationException violation = violates("fk_preorder_batch");

        assertThatThrownBy(this::acceptByAdmin).isSameAs(violation);
        verify(transaction, times(2)).accept(any(), any());
    }

    private void acceptByAdmin() {
        service.acceptByAdmin(CUSTOMER_ID, PRODUCT_ID, OPTION_ID, "key-00000001", "전화 접수", null);
    }

    /** 트랜잭션이 그 제약 위반으로 롤백된 것처럼 만든다. MySQL 처럼 표 이름 · 백틱이 붙어도 이름만 본다. */
    private DataIntegrityViolationException violates(String constraint) {
        DataIntegrityViolationException violation = violation(constraint);
        given(transaction.accept(any(), any())).willThrow(violation);
        return violation;
    }

    private static DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("duplicate",
                new ConstraintViolationException("duplicate",
                        new SQLException("duplicate", "23000", 1062), "`" + constraint + "`"));
    }
}
