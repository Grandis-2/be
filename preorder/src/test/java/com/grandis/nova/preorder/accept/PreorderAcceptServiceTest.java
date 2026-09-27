package com.grandis.nova.preorder.accept;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.admission.AdmissionTicketVerifier;
import com.grandis.nova.preorder.catalog.CatalogReader;
import com.grandis.nova.preorder.preorder.Preorder;
import com.grandis.nova.preorder.preorder.PreorderRepository;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * UNIQUE · FK 충돌을 계약의 오류로 바꾸는 분기. 동시성 테스트는 경합이 실제로 일어났는지 보장하지 못하므로
 * (차례로 들어오면 재전송 확인이 먼저 잡는다) 분기 자체는 여기서 충돌을 직접 만들어 확인한다.
 */
class PreorderAcceptServiceTest {

    static final Long CUSTOMER_ID = 1024L;
    static final Long PRODUCT_ID = 101L;

    PreorderAcceptTransaction transaction;
    PreorderRepository preorders;
    PreorderAcceptService service;

    @BeforeEach
    void setUp() {
        transaction = mock(PreorderAcceptTransaction.class);
        preorders = mock(PreorderRepository.class);
        CatalogReader catalogReader = mock(CatalogReader.class);
        given(catalogReader.findProduct(PRODUCT_ID)).willReturn(Optional.empty());
        service = new PreorderAcceptService(mock(AdmissionTicketVerifier.class), catalogReader, transaction, preorders,
                new SimpleMeterRegistry());
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
        Preorder existing = mock(Preorder.class);
        given(existing.getPreorderToken()).willReturn("9f1c2d3e");
        given(preorders.findFirstByCustomerIdAndProductIdAndStatusNot(CUSTOMER_ID, PRODUCT_ID,
                PreorderStatus.CANCELED)).willReturn(Optional.of(existing));

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
    void 회원_FK_충돌이면_404_MEMBER_NOT_FOUND() {
        violates("fk_preorder_customer");

        assertThatThrownBy(this::acceptByAdmin)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(PreorderErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    void 모르는_제약이면_삼키지_않고_그대로_올린다() {
        DataIntegrityViolationException violation = violates("uq_something_else");

        assertThatThrownBy(this::acceptByAdmin).isSameAs(violation);
    }

    private void acceptByAdmin() {
        service.acceptByAdmin(CUSTOMER_ID, PRODUCT_ID, 1002L, "key-00000001", "전화 접수", null);
    }

    /** 트랜잭션이 그 제약 위반으로 롤백된 것처럼 만든다. MySQL 처럼 표 이름 · 백틱이 붙어도 이름만 본다. */
    private DataIntegrityViolationException violates(String constraint) {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("duplicate",
                new ConstraintViolationException("duplicate",
                        new SQLException("duplicate", "23000", 1062), "`" + constraint + "`"));
        given(transaction.accept(any(), any())).willThrow(violation);
        return violation;
    }
}
