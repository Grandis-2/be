package com.grandis.nova.preorder.accept.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.admission.AdmissionTicket;
import com.grandis.nova.preorder.admission.AdmissionTicketVerifier;
import com.grandis.nova.preorder.integration.catalog.CatalogReader;
import com.grandis.nova.preorder.integration.catalog.ProductCatalog;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.web.ValidationFailures;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 사전예약 접수. 트랜잭션 밖의 일(입장권 검증 · 카탈로그 조회 · UNIQUE 충돌 해석)을 맡고
 * 트랜잭션은 {@link PreorderAcceptTransaction} 에 맡긴다.
 *
 * 카탈로그 조회는 캐시가 비었으면 catalog 를 HTTP 로 부르므로 트랜잭션 밖에서 한다.
 * UNIQUE 충돌은 트랜잭션이 롤백된 뒤 제약 이름으로 가른다 — 삼키지 않고 계약의 오류로 바꾼다.
 */
@Service
public class PreorderAcceptService {

    static final String UQ_ACTIVE = "uq_preorder_active";
    static final String UQ_ADMISSION = "uq_preorder_admission";
    static final String UQ_IDEMPOTENCY = "uq_preorder_idempotency";
    static final String FK_CUSTOMER = "fk_preorder_customer";

    /** 접수 시간 · 결과(accepted · replayed · 오류 코드)별 건수. */
    static final String ACCEPT_METRIC = "preorder.accept";

    private final AdmissionTicketVerifier ticketVerifier;
    private final CatalogReader catalogReader;
    private final PreorderAcceptTransaction transaction;
    private final Preorders preorders;
    private final MeterRegistry meterRegistry;

    public PreorderAcceptService(AdmissionTicketVerifier ticketVerifier, CatalogReader catalogReader,
                                 PreorderAcceptTransaction transaction, Preorders preorders,
                                 MeterRegistry meterRegistry) {
        this.ticketVerifier = ticketVerifier;
        this.catalogReader = catalogReader;
        this.transaction = transaction;
        this.preorders = preorders;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 사용자 접수. 입장권은 항상 필요하다(게이트웨이가 통과 요청에도 붙인다).
     *
     * @param queryProductId 게이트웨이가 대기열을 고른 쿼리 값. 본문과 달라야 할 이유가 없다
     */
    public AcceptResult acceptByCustomer(Long customerId, Long queryProductId, Long productId, Long optionId,
                                         String idempotencyKey, String admissionTicket) {
        return measured(() -> acceptVerified(customerId, queryProductId, productId, optionId, idempotencyKey,
                admissionTicket));
    }

    /** 관리자 대신 접수. 같은 트랜잭션 · 같은 규칙이고 입장권만 없다. */
    public AcceptResult acceptByAdmin(Long customerId, Long productId, Long optionId, String idempotencyKey,
                                      String reason, String internalNote) {
        return measured(() -> accept(new AcceptCommand(customerId, productId, optionId, idempotencyKey, null,
                EventActor.ADMIN, reason, internalNote)));
    }

    private AcceptResult acceptVerified(Long customerId, Long queryProductId, Long productId, Long optionId,
                                        String idempotencyKey, String admissionTicket) {
        if (!queryProductId.equals(productId)) {
            throw ValidationFailures.of("productId", "쿼리의 productId 와 같아야 합니다.");
        }
        if (admissionTicket == null || admissionTicket.isBlank()) {
            throw new BusinessException(PreorderErrorCode.ADMISSION_TICKET_REQUIRED);
        }
        AdmissionTicket ticket = ticketVerifier.verify(admissionTicket, productId, customerId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.ADMISSION_TICKET_INVALID));
        return accept(new AcceptCommand(customerId, productId, optionId, idempotencyKey, ticket.id(),
                EventActor.USER, null, null));
    }

    /** 결과 태그는 accepted · replayed · 오류 코드(유한한 값)이고, 그 밖의 예외는 error 로 센다. */
    private AcceptResult measured(Supplier<AcceptResult> accept) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            AcceptResult result = accept.get();
            outcome = result.replayed() ? "replayed" : "accepted";
            return result;
        } catch (BusinessException e) {
            outcome = e.errorCode().name();
            throw e;
        } finally {
            sample.stop(meterRegistry.timer(ACCEPT_METRIC, "outcome", outcome));
        }
    }

    private AcceptResult accept(AcceptCommand command) {
        Optional<ProductCatalog> product = catalogReader.findProduct(command.productId());
        try {
            return transaction.accept(command, product);
        } catch (DataIntegrityViolationException e) {
            return resolveConflict(command, e);
        }
    }

    private AcceptResult resolveConflict(AcceptCommand command, DataIntegrityViolationException e) {
        String constraint = ConstraintNames.of(e).orElseThrow(() -> e);
        return switch (constraint) {
            // 같은 키가 다른 모델로 동시에 들어오면 서로 다른 회차를 잠가 재전송 확인을 지나칠 수 있다. 다시 본다.
            case UQ_IDEMPOTENCY -> transaction.findReplay(command).orElseThrow(() -> e);
            case UQ_ACTIVE -> throw new BusinessException(PreorderErrorCode.ACTIVE_PREORDER_EXISTS,
                    preorders.findActive(command.customerId(), command.productId())
                            .map(existing -> Map.<String, Object>of("existingPreorderId", existing.preorderToken()))
                            .orElse(null));
            case UQ_ADMISSION -> throw new BusinessException(PreorderErrorCode.ADMISSION_TICKET_USED);
            case FK_CUSTOMER -> throw new BusinessException(PreorderErrorCode.MEMBER_NOT_FOUND);
            default -> throw e;
        };
    }
}
