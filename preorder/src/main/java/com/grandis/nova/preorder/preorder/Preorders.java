package com.grandis.nova.preorder.preorder;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.preorder.preorder.domain.AdminPreorderSearchQuery;
import com.grandis.nova.preorder.preorder.domain.Preorder;
import com.grandis.nova.preorder.preorder.domain.PreorderEvent;
import com.grandis.nova.preorder.preorder.domain.PreorderEventRepository;
import com.grandis.nova.preorder.preorder.domain.PreorderRepository;
import com.grandis.nova.preorder.preorder.domain.PreorderSpecifications;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 예약 조회(모듈 공개 API). 값은 {@link PreorderSnapshot} 으로만 내준다. 바꾸는 일은 {@link PreorderLedger} 가 한다. */
@Service
@Transactional(readOnly = true)
public class Preorders {

    static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final PreorderRepository preorders;
    private final PreorderEventRepository events;
    private final AdminPreorderSearchQuery adminSearch;

    Preorders(PreorderRepository preorders, PreorderEventRepository events, AdminPreorderSearchQuery adminSearch) {
        this.preorders = preorders;
        this.events = events;
        this.adminSearch = adminSearch;
    }

    public Optional<PreorderSnapshot> findByToken(String preorderToken) {
        return preorders.findByPreorderToken(preorderToken).map(Preorder::toSnapshot);
    }

    /** @throws BusinessException PREORDER_NOT_FOUND */
    public PreorderSnapshot getByToken(String preorderToken) {
        return preorders.getByToken(preorderToken).toSnapshot();
    }

    public Optional<PreorderSnapshot> findById(UUID preorderId) {
        return preorders.findById(preorderId).map(Preorder::toSnapshot);
    }

    /** id → 예약. 없는 id 는 빠진다. */
    public Map<UUID, PreorderSnapshot> findAllById(Collection<UUID> preorderIds) {
        return preorders.findAllById(preorderIds).stream()
                .collect(Collectors.toMap(Preorder::getId, Preorder::toSnapshot));
    }

    /** 회원이 그 접수 키로 만든 예약(취소된 것 포함). */
    public Optional<PreorderSnapshot> findByIdempotencyKey(UUID customerId, String idempotencyKey) {
        return preorders.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey).map(Preorder::toSnapshot);
    }

    /** 회원의 그 상품 진행 중(취소 완료가 아닌) 예약. */
    public Optional<PreorderSnapshot> findActive(UUID customerId, UUID productId) {
        return preorders.findFirstByCustomerIdAndProductIdAndStatusNot(customerId, productId, PreorderStatus.CANCELED)
                .map(Preorder::toSnapshot);
    }

    /** 회원의 그 모델 마지막 접수 시각(취소된 예약 포함). 접수한 적이 없으면 비어 있다. */
    public Optional<Instant> findLastAcceptedAt(UUID customerId, UUID productId) {
        return preorders.findLastAcceptedAt(customerId, productId);
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

    /** 회원의 예약(최신순)을 커서 자리 다음부터 최대 limit 건. 커서가 없으면 처음부터다. */
    public List<PreorderSnapshot> findForCustomer(UUID customerId, PreorderStatus status, UUID productId,
                                                  Instant afterCreatedAt, UUID afterId, int limit) {
        Specification<Preorder> specification = PreorderSpecifications.allOf(
                PreorderSpecifications.customer(customerId),
                PreorderSpecifications.status(status),
                PreorderSpecifications.product(productId),
                PreorderSpecifications.after(afterCreatedAt, afterId));
        // 총계를 세지 않는다. findAll(Specification, Pageable) 은 쓰지 않는 COUNT 까지 돌린다.
        return preorders.findBy(specification, query -> query.sortBy(NEWEST_FIRST).limit(limit).all()).stream()
                .map(Preorder::toSnapshot)
                .toList();
    }

    /** 관리자 검색(최신순 오프셋 페이지). */
    public OffsetPage<PreorderSnapshot> searchForAdmin(AdminPreorderSearch search, int page, int size) {
        List<UUID> ids = adminSearch.findIds(search, page, size);
        Map<UUID, Preorder> byId = preorders.findAllById(ids).stream()
                .collect(Collectors.toMap(Preorder::getId, Function.identity()));
        List<PreorderSnapshot> items = ids.stream().map(byId::get).map(Preorder::toSnapshot).toList();
        return OffsetPage.of(items, page, size, adminSearch.count(search));
    }
}
