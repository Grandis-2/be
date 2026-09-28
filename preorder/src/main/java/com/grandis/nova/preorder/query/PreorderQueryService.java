package com.grandis.nova.preorder.query;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.Cursor;
import com.grandis.nova.common.CursorPage;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.ShipmentBatch;
import com.grandis.nova.preorder.campaign.ShipmentBatchRepository;
import com.grandis.nova.preorder.preorder.Preorder;
import com.grandis.nova.preorder.preorder.PreorderEvent;
import com.grandis.nova.preorder.preorder.PreorderEventRepository;
import com.grandis.nova.preorder.preorder.PreorderRepository;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.syncjob.PreorderSyncJob;
import com.grandis.nova.preorder.syncjob.PreorderSyncJobRepository;
import com.grandis.nova.preorder.syncjob.SyncAttemptReader;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;
import com.grandis.nova.preorder.web.Viewer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 예약 조회. 쓰기가 없어 읽기 전용 트랜잭션이다.
 *
 * 목록은 예약을 먼저 읽고 배송 차수 · 작업을 id 묶음으로 한 번에 읽는다(행마다 다시 묻지 않는다).
 * 사용자 목록은 커서, 관리자 목록은 오프셋이다 — 접수가 계속 들어오는 사용자 목록에서 오프셋은 항목이 밀린다.
 */
@Service
@Transactional(readOnly = true)
public class PreorderQueryService {

    static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final PreorderRepository preorders;
    private final ShipmentBatchRepository batches;
    private final PreorderEventRepository events;
    private final PreorderSyncJobRepository syncJobs;
    private final SyncAttemptReader syncAttempts;

    public PreorderQueryService(PreorderRepository preorders, ShipmentBatchRepository batches,
                                PreorderEventRepository events, PreorderSyncJobRepository syncJobs,
                                SyncAttemptReader syncAttempts) {
        this.preorders = preorders;
        this.batches = batches;
        this.events = events;
        this.syncJobs = syncJobs;
        this.syncAttempts = syncAttempts;
    }

    /** 내 예약 목록(최신순). 한 건 더 읽어 다음 페이지가 있는지 본다. */
    public CursorPage<PreorderView.Summary> findMine(Long customerId, PreorderStatus status, Long productId,
                                                     String cursor, int size) {
        Position position = Position.of(cursor);
        Specification<Preorder> specification = PreorderSpecifications.allOf(
                PreorderSpecifications.customer(customerId),
                PreorderSpecifications.status(status),
                PreorderSpecifications.product(productId),
                PreorderSpecifications.after(position.createdAt(), position.id()));

        // 총계를 세지 않는다. findAll(Specification, Pageable) 은 쓰지 않는 COUNT 까지 돌린다.
        List<Preorder> found = new ArrayList<>(preorders.findBy(specification,
                query -> query.sortBy(NEWEST_FIRST).limit(size + 1).all()));
        boolean hasNext = found.size() > size;
        if (hasNext) {
            found.removeLast();
        }
        List<PreorderView.Summary> items = withBatches(found, PreorderView.Summary::new);
        if (!hasNext) {
            return CursorPage.last(items);
        }
        Preorder last = found.getLast();
        return CursorPage.of(items, Cursor.encode(last.getCreatedAt().toString(), last.getId()));
    }

    /** 예약 하나. 본인과 관리자만 볼 수 있고, 남의 예약은 존재를 알리지 않는다(404). */
    public PreorderView.Summary findOne(Viewer viewer, String preorderToken) {
        Preorder preorder = require(viewer, preorderToken);
        return new PreorderView.Summary(preorder, batches.getAssigned(preorder));
    }

    /** 상태 전이 이력(번호 순). 접근 규칙은 상세와 같다. */
    public List<PreorderEvent> findHistory(Viewer viewer, String preorderToken) {
        return events.findByPreorderIdOrderByEventSequence(require(viewer, preorderToken).getId());
    }

    public OffsetPage<PreorderView.AdminSummary> findForAdmin(AdminPreorderFilter filter, int page, int size) {
        Specification<Preorder> specification = PreorderSpecifications.allOf(
                PreorderSpecifications.status(filter.status()),
                PreorderSpecifications.customer(filter.customerId()),
                PreorderSpecifications.product(filter.productId()),
                PreorderSpecifications.createdFrom(filter.from()),
                PreorderSpecifications.createdUntil(filter.to()),
                PreorderSpecifications.registerJobStatus(filter.registerJobStatus()));

        Page<Preorder> found = preorders.findAll(specification, PageRequest.of(page, size, NEWEST_FIRST));
        Map<Long, SyncJobStatus> registerStatuses = registerJobStatuses(found.getContent());
        List<PreorderView.AdminSummary> items = withBatches(found.getContent(), (preorder, batch) ->
                new PreorderView.AdminSummary(preorder, batch, registerStatuses.get(preorder.getId())));
        return OffsetPage.of(items, page, size, found.getTotalElements());
    }

    /** 관리자 상세. 작업 · 시도 · 이력까지 함께 읽는다. */
    public PreorderView.AdminDetail findOneForAdmin(String preorderToken) {
        Preorder preorder = preorders.getByToken(preorderToken);
        List<PreorderSyncJob> jobs = syncJobs.findByPreorderIdOrderByJobType(preorder.getId());
        return new PreorderView.AdminDetail(preorder, batches.getAssigned(preorder), jobs,
                syncAttempts.findByJobIds(jobs.stream().map(PreorderSyncJob::getId).toList()),
                events.findByPreorderIdOrderByEventSequence(preorder.getId()));
    }

    private Preorder require(Viewer viewer, String preorderToken) {
        Preorder preorder = preorders.getByToken(preorderToken);
        if (!viewer.canSee(preorder.getCustomerId())) {
            throw new BusinessException(PreorderErrorCode.PREORDER_NOT_FOUND);
        }
        return preorder;
    }

    private <T> List<T> withBatches(List<Preorder> found, BatchMapper<T> mapper) {
        Map<Long, ShipmentBatch> byId = batches.findAllById(
                        found.stream().map(Preorder::getShipmentBatchId).distinct().toList()).stream()
                .collect(Collectors.toMap(ShipmentBatch::getId, Function.identity()));
        return found.stream().map(preorder -> mapper.map(preorder, byId.get(preorder.getShipmentBatchId()))).toList();
    }

    private Map<Long, SyncJobStatus> registerJobStatuses(Collection<Preorder> found) {
        if (found.isEmpty()) {
            return Map.of();
        }
        return syncJobs.findByPreorderIdInAndJobType(found.stream().map(Preorder::getId).toList(),
                        SyncJobType.REGISTER).stream()
                .collect(Collectors.toMap(PreorderSyncJob::getPreorderId, PreorderSyncJob::getStatus));
    }

    @FunctionalInterface
    private interface BatchMapper<T> {
        T map(Preorder preorder, ShipmentBatch batch);
    }
}
