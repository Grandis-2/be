package com.grandis.nova.preorder.accept.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.admission.AdmissionTicket;
import com.grandis.nova.preorder.admission.AdmissionTicketVerifier;
import com.grandis.nova.preorder.campaign.CachedCampaign;
import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.integration.catalog.CatalogReader;
import com.grandis.nova.preorder.integration.catalog.ProductCatalog;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.web.ValidationFailures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
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
 * 트랜잭션은 격벽({@link AcceptBulkhead}) 안에서만 연다 — 넘친 접수는 커넥션을 잡기 전에 503 으로 돌려보낸다.
 */
@Service
public class PreorderAcceptService {

    static final String UQ_ACTIVE = "uq_preorder_active";
    static final String UQ_ADMISSION = "uq_preorder_admission";
    static final String UQ_IDEMPOTENCY = "uq_preorder_idempotency";
    static final String FK_BATCH = "fk_preorder_batch";

    /** 접수 시간 · 결과(accepted · replayed · 오류 코드)별 건수. */
    static final String ACCEPT_METRIC = "preorder.accept";
    /**
     * 업무 지표 — 새 예약(success) · 업무 거절(4xx, rejected) · 그 밖의 실패(5xx · 예외, error)를 접수 경로(user · admin) · 상품별로 센다.
     * 상품 라벨은 {@link AcceptProductTags} 가 정한다.
     */
    static final String CREATED_METRIC = "nova.preorder.created";
    static final String USER_CHANNEL = "user";
    static final String ADMIN_CHANNEL = "admin";

    private static final Logger log = LoggerFactory.getLogger(PreorderAcceptService.class);

    private final AdmissionTicketVerifier ticketVerifier;
    private final CatalogReader catalogReader;
    private final PreorderAcceptTransaction transaction;
    private final AcceptBulkhead bulkhead;
    private final Campaigns campaigns;
    private final Preorders preorders;
    private final AcceptProductTags productTags;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public PreorderAcceptService(AdmissionTicketVerifier ticketVerifier, CatalogReader catalogReader,
                                 PreorderAcceptTransaction transaction, AcceptBulkhead bulkhead,
                                 Campaigns campaigns, Preorders preorders, AcceptProductTags productTags,
                                 MeterRegistry meterRegistry, Clock clock) {
        this.ticketVerifier = ticketVerifier;
        this.catalogReader = catalogReader;
        this.transaction = transaction;
        this.bulkhead = bulkhead;
        this.campaigns = campaigns;
        this.preorders = preorders;
        this.productTags = productTags;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        // 처음부터 0 으로 내놓는다 — 시계열이 없으면 "접수 중인데 새 예약 0건" 경보가 값 없음으로 빠진다
        registerCreated(meterRegistry, AcceptProductTags.OTHER);
    }

    /** 그 상품 라벨의 업무 지표를 결과 · 경로 조합마다 0 으로 만들어 둔다. */
    static void registerCreated(MeterRegistry meterRegistry, String productTag) {
        for (String channel : List.of(USER_CHANNEL, ADMIN_CHANNEL)) {
            for (String result : List.of("success", "rejected", "error")) {
                createdCounter(meterRegistry, result, channel, productTag);
            }
        }
    }

    private static Counter createdCounter(MeterRegistry meterRegistry, String result, String channel,
                                          String productTag) {
        return meterRegistry.counter(CREATED_METRIC, "result", result, "channel", channel,
                AcceptProductTags.PRODUCT_TAG, productTag);
    }

    /**
     * 사용자 접수. 입장권은 항상 필요하다(게이트웨이가 통과 요청에도 붙인다).
     *
     * @param queryProductId 게이트웨이가 대기열을 고른 쿼리 값. 본문과 달라야 할 이유가 없다
     */
    public AcceptResult acceptByCustomer(UUID customerId, UUID queryProductId, UUID productId, UUID optionId,
                                         String idempotencyKey, String admissionTicket) {
        return measured(USER_CHANNEL, productId, () -> acceptVerified(customerId, queryProductId, productId, optionId,
                idempotencyKey, admissionTicket));
    }

    /** 관리자 대신 접수. 같은 트랜잭션 · 같은 규칙이고 입장권만 없다. */
    public AcceptResult acceptByAdmin(UUID customerId, UUID productId, UUID optionId, String idempotencyKey,
                                      String reason, String internalNote) {
        return measured(ADMIN_CHANNEL, productId, () -> accept(new AcceptCommand(customerId, productId, optionId, idempotencyKey,
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
    private AcceptResult measured(String channel, UUID productId, Supplier<AcceptResult> accept) {
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
                createdCounter(meterRegistry, result, channel, productTags.of(productId)).increment();
            }
        }
    }

    private AcceptResult accept(AcceptCommand command) {
        try {
            return acceptOrResolve(command);
        } catch (PessimisticLockingFailureException e) {
            // 교착 · 잠금 대기 시한 — 트랜잭션은 롤백돼 순번도 쓰지 않았다. 같은 접수 키로 다시 보내면 한 번만 접수된다
            log.warn("접수가 잠금을 얻지 못해 다시 보내게 한다 productId={}: {}", command.productId(), e.toString());
            throw bulkhead.retryLater();
        }
    }

    /**
     * 회차 캐시가 회차 없음 · 오픈 전이라 하면 회차를 잠그지 않고 판정한다 — 오픈 직전에 몰린 접수가 트랜잭션을 열지 않게.
     * 캐시는 회차가 바뀌면 커밋 뒤 비우므로 비우기를 놓쳤을 때만 수명만큼 옛 판정이다.
     * 그 밖(접수 중 · 마감, 또는 판정을 지나면)은 접수 트랜잭션이 회차 행으로 판정하고 잠근 뒤 마지막으로 본다.
     */
    private AcceptResult acceptOrResolve(AcceptCommand command) {
        Optional<ProductCatalog> product = catalogReader.findProduct(command.productId());
        try {
            // 캐시 미스 · 잠금 전 판정의 DB 읽기도 격벽 몫이다 — 허가 하나로 판정부터 접수까지 한다
            return bulkhead.call(command.productId(), () -> settleThenAccept(command, product));
        } catch (DataIntegrityViolationException e) {
            if (ConstraintNames.of(e).filter(FK_BATCH::equals).isEmpty()) {
                return resolveConflict(command, e);
            }
            // 캐시에서 고른 차수가 그사이 교체됐다(오픈을 넘겨 커밋된 교체 · 비우기 놓침). 비우고 한 번만 다시 — 다시는 DB 에서 고른다
            log.warn("캐시의 배송 차수가 교체돼 비우고 다시 접수한다 productId={}", command.productId());
            campaigns.evictCached(command.productId());
            try {
                return bulkhead.call(command.productId(), () -> transaction.accept(command, product));
            } catch (DataIntegrityViolationException retried) {
                return resolveConflict(command, retried);
            }
        }
    }

    private AcceptResult settleThenAccept(AcceptCommand command, Optional<ProductCatalog> product) {
        Optional<CachedCampaign> cached = campaigns.findCached(command.productId());
        Instant now = clock.instant();
        if (cached.filter(campaign -> campaign.opened(now)).isEmpty()) {
            Optional<AcceptResult> settled = transaction.settleBeforeLock(command, product,
                    cached.map(CachedCampaign::forAccept));
            if (settled.isPresent()) {
                return settled.get();
            }
        }
        return transaction.accept(command, product);
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
            default -> throw e;
        };
    }
}
