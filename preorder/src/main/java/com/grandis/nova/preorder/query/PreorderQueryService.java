package com.grandis.nova.preorder.query;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.Cursor;
import com.grandis.nova.common.CursorPage;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.campaign.ShipmentBatchSnapshot;
import com.grandis.nova.preorder.preorder.AdminPreorderSearch;
import com.grandis.nova.preorder.preorder.PreorderHistoryEntry;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.syncjob.SyncAttemptReader;
import com.grandis.nova.preorder.syncjob.SyncJobSnapshot;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobs;
import com.grandis.nova.preorder.web.Viewer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 예약 조회. 쓰기가 없어 읽기 전용 트랜잭션이다.
 *
 * 목록은 예약을 먼저 읽고 배송 차수 · 작업을 id 묶음으로 한 번에 읽는다(행마다 다시 묻지 않는다).
 * 사용자 목록은 커서, 관리자 목록은 오프셋이다 — 접수가 계속 들어오는 사용자 목록에서 오프셋은 항목이 밀린다.
 */
@Service
@Transactional(readOnly = true)
public class PreorderQueryService {

    private final Preorders preorders;
    private final Campaigns campaigns;
    private final SyncJobs syncJobs;
    private final SyncAttemptReader syncAttempts;

    PreorderQueryService(Preorders preorders, Campaigns campaigns, SyncJobs syncJobs,
                         SyncAttemptReader syncAttempts) {
        this.preorders = preorders;
        this.campaigns = campaigns;
        this.syncJobs = syncJobs;
        this.syncAttempts = syncAttempts;
    }

    /** 내 예약 목록(최신순). 한 건 더 읽어 다음 페이지가 있는지 본다. */
    public CursorPage<PreorderView.Summary> findMine(Long customerId, PreorderStatus status, Long productId,
                                                     String cursor, int size) {
        Position position = Position.of(cursor);
        List<PreorderSnapshot> found = new ArrayList<>(preorders.findForCustomer(customerId, status, productId,
                position.createdAt(), position.id(), size + 1));
        boolean hasNext = found.size() > size;
        if (hasNext) {
            found.removeLast();
        }
        List<PreorderView.Summary> items = withBatches(found, PreorderView.Summary::new);
        if (!hasNext) {
            return CursorPage.last(items);
        }
        PreorderSnapshot last = found.getLast();
        return CursorPage.of(items, Cursor.encode(last.createdAt().toString(), last.id()));
    }

    /** 예약 하나. 본인과 관리자만 볼 수 있고, 남의 예약은 존재를 알리지 않는다(404). */
    public PreorderView.Summary findOne(Viewer viewer, String preorderToken) {
        PreorderSnapshot preorder = require(viewer, preorderToken);
        return new PreorderView.Summary(preorder, campaigns.getBatch(preorder.shipmentBatchId()));
    }

    /** 상태 전이 이력(번호 순). 접근 규칙은 상세와 같다. */
    public List<PreorderHistoryEntry> findHistory(Viewer viewer, String preorderToken) {
        return preorders.history(require(viewer, preorderToken).id());
    }

    public OffsetPage<PreorderView.AdminSummary> findForAdmin(AdminPreorderFilter filter, int page, int size) {
        SyncJobStatus jobStatus = filter.registerJobStatus();
        OffsetPage<PreorderSnapshot> found = preorders.searchForAdmin(new AdminPreorderSearch(filter.status(),
                filter.customerId(), filter.productId(), filter.from(), filter.to(),
                jobStatus == null ? null : jobStatus.name()), page, size);
        Map<Long, SyncJobStatus> registerStatuses = registerJobStatuses(found.items());
        List<PreorderView.AdminSummary> items = withBatches(found.items(), (preorder, batch) ->
                new PreorderView.AdminSummary(preorder, batch, registerStatuses.get(preorder.id())));
        return OffsetPage.of(items, page, size, found.total());
    }

    /** 관리자 상세. 작업 · 시도 · 이력까지 함께 읽는다. */
    public PreorderView.AdminDetail findOneForAdmin(String preorderToken) {
        PreorderSnapshot preorder = preorders.getByToken(preorderToken);
        List<SyncJobSnapshot> jobs = syncJobs.findByPreorder(preorder.id());
        return new PreorderView.AdminDetail(preorder, campaigns.getBatch(preorder.shipmentBatchId()), jobs,
                syncAttempts.findByJobIds(jobs.stream().map(SyncJobSnapshot::id).toList()),
                preorders.history(preorder.id()));
    }

    private PreorderSnapshot require(Viewer viewer, String preorderToken) {
        PreorderSnapshot preorder = preorders.getByToken(preorderToken);
        if (!viewer.canSee(preorder.customerId())) {
            throw new BusinessException(PreorderErrorCode.PREORDER_NOT_FOUND);
        }
        return preorder;
    }

    private <T> List<T> withBatches(List<PreorderSnapshot> found, BatchMapper<T> mapper) {
        Map<Long, ShipmentBatchSnapshot> byId = campaigns.findBatches(
                found.stream().map(PreorderSnapshot::shipmentBatchId).distinct().toList());
        return found.stream().map(preorder -> mapper.map(preorder, byId.get(preorder.shipmentBatchId()))).toList();
    }

    private Map<Long, SyncJobStatus> registerJobStatuses(Collection<PreorderSnapshot> found) {
        return syncJobs.registerStatuses(found.stream().map(PreorderSnapshot::id).toList());
    }

    @FunctionalInterface
    private interface BatchMapper<T> {
        T map(PreorderSnapshot preorder, ShipmentBatchSnapshot batch);
    }
}
