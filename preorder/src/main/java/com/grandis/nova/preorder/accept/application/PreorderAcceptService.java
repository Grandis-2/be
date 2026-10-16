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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
    /** 업무 지표 — 새 예약(success) · 업무 거절(4xx, rejected) · 그 밖의 실패(5xx · 예외, error)를 접수 경로(user · admin)별로 센다. */
    static final String CREATED_METRIC = "nova.preorder.created";
    static final String USER_CHANNEL = "user";
    static final String ADMIN_CHANNEL = "admin";

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
        // 처음부터 0 으로 내놓는다 — 시계열이 없으면 "접수 중인데 새 예약 0건" 경보가 값 없음으로 빠진다
        for (String channel : List.of(USER_CHANNEL, ADMIN_CHANNEL)) {
            for (String result : List.of("success", "rejected", "error")) {
                meterRegistry.counter(CREATED_METRIC, "result", result, "channel", channel);
            }
        }
    }

    /**
     * 사용자 접수. 입장권은 항상 필요하다(게이트웨이가 통과 요청에도 붙인다).
     *
     * @param queryProductId 게이트웨이가 대기열을 고른 쿼리 값. 본문과 달라야 할 이유가 없다
     */
    public AcceptResult acceptByCustomer(UUID customerId, UUID queryProductId, UUID productId, UUID optionId,
                                         String idempotencyKey, String admissionTicket) {
        return measured(USER_CHANNEL, () -> acceptVerified(customerId, queryProductId, productId, optionId,
                idempotencyKey, admissionTicket));
    }

    /** 관리자 대신 접수. 같은 트랜잭션 · 같은 규칙이고 입장권만 없다. */
    public AcceptResult acceptByAdmin(UUID customerId, UUID productId, UUID optionId, String idempotencyKey,
                                      String reason, String internalNote) {
        return measured(ADMIN_CHANNEL, () -> accept(new AcceptCommand(customerId, productId, optionId, idempotencyKey,
                null, EventActor.ADMIN, reason, internalNote)));
    }

    private AcceptResult acceptVerified(UUID customerId, UUID queryProductId, UUID productId, UUID optionId,
                                        String idempotencyKey, String admissionTicket) {
        if (!queryProductId.equals(productId)) {
            throw ValidationFailures.of("productId", "쿼리의 productId 와 같아야 합니다.");
        }
        if (admissionTicket == null || admissionTicket.isBlank()) {
            throw new BusinessException(PreorderErrorCode.ADMISSION_TICKET_REQUIRED);
        }
        AdmissionTicket ticket = ticketVerifier.verify(admissionTicket, productId, customerId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.ADMISSION_TICKET_INVALID));
        AcceptCommand command = new AcceptCommand(customerId, productId, optionId, idempotencyKey, ticket,
                EventActor.USER, null, null);
        if (ticket.expired()) {
            // 만료된 입장권으로는 새로 접수하지 않는다. 같은 접수 키의 재전송이면 기존 예약을 돌려준다
            return transaction.findReplay(command)
                    .orElseThrow(() -> new BusinessException(PreorderErrorCode.ADMISSION_TICKET_INVALID));
        }
        return accept(command);
    }

    /**
     * 결과 태그는 accepted · replayed · 오류 코드(유한한 값)이고, 그 밖의 예외는 error 로 센다.
     * 업무 지표는 접수 트랜잭션이 끝난 뒤 세고, 같은 접수 키의 재전송은 새 예약이 아니라 세지 않는다.
     */
    private AcceptResult measured(String channel, Supplier<AcceptResult> accept) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        String result = "error";
        try {
            AcceptResult accepted = accept.get();
            outcome = accepted.replayed() ? "replayed" : "accepted";
            result = accepted.replayed() ? null : "success";
            return accepted;
        } catch (BusinessException e) {
            outcome = e.errorCode().name();
            // 다른 서비스 장애(503) 같은 5xx 는 업무 거절이 아니다
            result = e.errorCode().status() < 500 ? "rejected" : "error";
            throw e;
        } finally {
            sample.stop(meterRegistry.timer(ACCEPT_METRIC, "outcome", outcome));
            if (result != null) {
                meterRegistry.counter(CREATED_METRIC, "result", result, "channel", channel).increment();
            }
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
