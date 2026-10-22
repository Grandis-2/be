package com.grandis.nova.preorder.preorder;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.preorder.domain.AdminPreorderSearchQuery;
import com.grandis.nova.preorder.preorder.domain.CustomerPreorderListQuery;
import com.grandis.nova.preorder.preorder.domain.CustomerStageQuery;
import com.grandis.nova.preorder.preorder.domain.Preorder;
import com.grandis.nova.preorder.preorder.domain.PaymentOverdueQuery;
import com.grandis.nova.preorder.preorder.domain.PreorderBacklogQuery;
import com.grandis.nova.preorder.preorder.domain.PreorderEvent;
import com.grandis.nova.preorder.preorder.domain.PreorderEventRepository;
import com.grandis.nova.preorder.preorder.domain.PreorderRepository;
import com.grandis.nova.preorder.preorder.domain.PreorderSpecifications;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 예약 조회(모듈 공개 API). 값은 {@link PreorderSnapshot} 으로만 내준다. 바꾸는 일은 {@link PreorderLedger} 가 한다.
 * 트랜잭션을 열지 않는다 — 잠그지 않는 읽기라 READ COMMITTED 에서는 묶어도 문장마다 따로 본다. 부른 쪽 트랜잭션이 있으면 그 안에서 읽는다.
 */
@Service
public class Preorders {

    private final PreorderRepository preorders;
    private final PreorderEventRepository events;
    private final AdminPreorderSearchQuery adminSearch;
    private final PreorderBacklogQuery backlog;
    private final PaymentOverdueQuery paymentOverdue;
    private final CustomerPreorderListQuery customerList;
    private final CustomerStageQuery customerStages;

    Preorders(PreorderRepository preorders, PreorderEventRepository events, AdminPreorderSearchQuery adminSearch,
              PreorderBacklogQuery backlog, PaymentOverdueQuery paymentOverdue, CustomerPreorderListQuery customerList,
              CustomerStageQuery customerStages) {
        this.preorders = preorders;
        this.events = events;
        this.adminSearch = adminSearch;
        this.backlog = backlog;
        this.paymentOverdue = paymentOverdue;
        this.customerList = customerList;
        this.customerStages = customerStages;
    }

    public Optional<PreorderSnapshot> findByToken(String preorderToken) {
        return preorders.findByPreorderToken(preorderToken).map(Preorder::toSnapshot);
    }

    /** @throws BusinessException PREORDER_NOT_FOUND */
    public PreorderSnapshot getByToken(String preorderToken) {
        return preorders.getByToken(preorderToken).toSnapshot();
    }

    public Optional<PreorderSnapshot> findById(UUID preorderId) {
        return preorders.lookup(preorderId).map(Preorder::toSnapshot);
    }

    /** @throws BusinessException PREORDER_NOT_FOUND */
    public PreorderSnapshot getById(UUID preorderId) {
        return findById(preorderId).orElseThrow(() -> new BusinessException(PreorderErrorCode.PREORDER_NOT_FOUND));
    }

    /** id → 예약. 없는 id 는 빠진다. */
    public Map<UUID, PreorderSnapshot> findAllById(Collection<UUID> preorderIds) {
        if (preorderIds.isEmpty()) {
            return Map.of();
        }
        return preorders.lookupAll(preorderIds).stream()
                .collect(Collectors.toMap(Preorder::getId, Preorder::toSnapshot));
    }

    /** 회원이 그 접수 키로 만든 예약(취소된 것 포함). */
    public Optional<PreorderSnapshot> findByIdempotencyKey(UUID customerId, String idempotencyKey) {
        return preorders.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey).map(Preorder::toSnapshot);
    }

    /**
     * 같은 접수 키의 예약과 그 모델의 마지막 접수 시각 — 접수 잠금 안에서 두 번 읽지 않게 한 문장으로.
     * 같은 키인지는 DB 가 비교한 결과로 가른다 — 칸의 콜레이션(끝 공백 무시)과 String.equals 가 다르다.
     */
    public AcceptHistory findAcceptHistory(UUID customerId, UUID productId, String idempotencyKey) {
        List<Object[]> rows = preorders.findAcceptHistory(customerId, productId, idempotencyKey);
        Optional<PreorderSnapshot> sameKey = rows.stream()
                .filter(row -> (Boolean) row[1])
                .map(row -> ((Preorder) row[0]).toSnapshot())
                .findFirst();
        Optional<Instant> lastAcceptedAt = rows.stream()
                .map(row -> ((Preorder) row[0]).toSnapshot())
                .filter(row -> row.productId().equals(productId))
                .map(PreorderSnapshot::createdAt)
                .max(Comparator.naturalOrder());
        return new AcceptHistory(sameKey, lastAcceptedAt);
    }

    /** 회원의 그 상품 진행 중(취소 완료가 아닌) 예약. */
    public Optional<PreorderSnapshot> findActive(UUID customerId, UUID productId) {
        return preorders.findFirstByCustomerIdAndProductIdAndStatusNot(customerId, productId, PreorderStatus.CANCELED)
                .map(Preorder::toSnapshot);
    }


    /** 상품의 그 상태 예약을 순번 순으로 최대 limit 건. */
    public List<PreorderSnapshot> findByProduct(UUID productId, Collection<PreorderStatus> statuses, int limit) {
        return preorders.findByProductIdAndStatusInOrderByQueuePosition(productId, statuses, Limit.of(limit)).stream()
                .map(Preorder::toSnapshot)
                .toList();
    }

    /** 그 상태로 들어간 마지막 이력. 취소 시도 순번(cancelSequence)과 취소를 시작한 주체를 가린다. */
    public Optional<PreorderHistoryEntry> lastTransitionTo(UUID preorderId, PreorderStatus toStatus) {
        return events.findFirstByPreorderIdAndToStatusOrderByEventSequenceDesc(preorderId, toStatus)
                .map(PreorderEvent::toHistoryEntry);
    }


    /** 주문 쪽에서 취소가 거절돼 결제 가능(REGISTERED)으로 돌아온 적이 있는가. */
    public boolean wasCancelRejectedToRegistered(UUID preorderId) {
        return events.existsByPreorderIdAndFromStatusAndToStatus(preorderId, PreorderStatus.CANCELING,
                PreorderStatus.REGISTERED);
    }

    /** 이력(번호 순). */
    public List<PreorderHistoryEntry> history(UUID preorderId) {
        return events.findByPreorderIdOrderByEventSequence(preorderId).stream()
                .map(PreorderEvent::toHistoryEntry)
                .toList();
    }

    public Map<PreorderStatus, Long> countByStatus() {
        Map<PreorderStatus, Long> counts = new EnumMap<>(PreorderStatus.class);
        preorders.countByStatus().forEach(row -> counts.put(row.getStatus(), row.getCount()));
        return counts;
    }

    /** 상품의 유효 예약 수 — 취소 중 · 취소 완료를 뺀다. */
    public long countActive(UUID productId) {
        return preorders.countByProductExcluding(productId, EnumSet.of(PreorderStatus.CANCELING, PreorderStatus.CANCELED));
    }

    /** 운영 현황용 예약 수. productId 가 null 이면 전체를 센다. */
    public PreorderBacklog backlog(UUID productId, Instant now) {
        return new PreorderBacklog(backlog.countByStatus(productId),
                backlog.countRegisteredPayableUntil(now.minus(PreorderSnapshot.PAYMENT_WINDOW), productId),
                backlog.findOldestCreatedAt(PreorderStatus.PENDING_SYNC, productId).orElse(null));
    }

    /** 결제 기한이 지나 만료할 예약의 공개 UUID. 기한이 오래된 것부터 limit 건. */
    public List<String> findPaymentOverdue(Instant now, int limit) {
        return paymentOverdue.findTokens(now.minus(PreorderSnapshot.PAYMENT_WINDOW), limit);
    }

    public PaymentOverdue countPaymentOverdue(Instant now) {
        return paymentOverdue.count(now.minus(PreorderSnapshot.PAYMENT_WINDOW));
    }

    /** 회원의 예약(최신순)을 커서 자리 다음부터 최대 limit 건. 커서가 없으면 처음부터다. */
    public List<PreorderSnapshot> findForCustomer(UUID customerId, PreorderStatus status, UUID productId,
                                                  Instant afterCreatedAt, UUID afterId, int limit) {
        Specification<Preorder> specification = PreorderSpecifications.allOf(
                PreorderSpecifications.customer(customerId),
                PreorderSpecifications.status(status),
                PreorderSpecifications.product(productId),
                PreorderSpecifications.after(afterCreatedAt, afterId));
        return customerList.findNewestFirst(specification, limit).stream()
                .map(Preorder::toSnapshot)
                .toList();
    }

    /** 회원의 예약 중 그 단계들 중 하나인 것(최신순)을 커서 자리 다음부터 최대 limit 건. 커서가 없으면 처음부터다. */
    public List<PreorderSnapshot> findForCustomerInStages(UUID customerId, PreorderStatus status, UUID productId,
                                                         List<StageCondition> anyOf, Instant afterCreatedAt, UUID afterId,
                                                         int limit) {
        List<UUID> ids = customerStages.findIds(Objects.requireNonNull(customerId), status, productId, anyOf,
                afterCreatedAt, afterId, limit);
        Map<UUID, Preorder> byId = (ids.isEmpty() ? List.<Preorder>of() : preorders.lookupAll(ids)).stream()
                .collect(Collectors.toMap(Preorder::getId, Function.identity()));
        return ids.stream().map(byId::get).map(Preorder::toSnapshot).toList();
    }

    /** 회원의 예약 중 단계마다 맞는 수. 받은 단계 순서대로 준다. */
    public List<Long> countForCustomerByStage(UUID customerId, List<StageCondition> stages) {
        return customerStages.countEach(Objects.requireNonNull(customerId), stages);
    }

    /** 회원의 진행 중인 예약(최신순)을 커서 자리 다음부터 최대 limit 건. 상품마다 많아야 한 건이다. */
    public List<PreorderSnapshot> findActiveForCustomer(UUID customerId, Instant afterCreatedAt, UUID afterId,
                                                        int limit) {
        Specification<Preorder> specification = PreorderSpecifications.allOf(
                PreorderSpecifications.customer(Objects.requireNonNull(customerId)),
                PreorderSpecifications.active(),
                PreorderSpecifications.after(afterCreatedAt, afterId));
        return customerList.findNewestFirst(specification, limit).stream()
                .map(Preorder::toSnapshot)
                .toList();
    }

    /** 관리자 검색(최신순 오프셋 페이지). */
    public OffsetPage<PreorderSnapshot> searchForAdmin(AdminPreorderSearch search, int page, int size) {
        List<UUID> ids = adminSearch.findIds(search, page, size);
        Map<UUID, Preorder> byId = (ids.isEmpty() ? List.<Preorder>of() : preorders.lookupAll(ids)).stream()
                .collect(Collectors.toMap(Preorder::getId, Function.identity()));
        List<PreorderSnapshot> items = ids.stream().map(byId::get).map(Preorder::toSnapshot).toList();
        return OffsetPage.of(items, page, size, adminSearch.count(search));
    }
}
