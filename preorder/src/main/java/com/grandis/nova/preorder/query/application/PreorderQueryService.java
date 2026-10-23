package com.grandis.nova.preorder.query.application;

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
import com.grandis.nova.preorder.preorder.StageCondition;
import com.grandis.nova.preorder.syncjob.SyncAttemptReader;
import com.grandis.nova.preorder.syncjob.SyncJobSnapshot;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;
import com.grandis.nova.preorder.syncjob.SyncJobs;
import com.grandis.nova.preorder.web.ValidationFailures;
import com.grandis.nova.preorder.web.Viewer;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 예약 조회. 트랜잭션을 열지 않는다 — 잠그지 않는 읽기라 READ COMMITTED 에서는 트랜잭션으로 묶어도 문장마다 따로 본다.
 *
 * 목록은 예약을 먼저 읽고 배송 차수 · 작업을 id 묶음으로 한 번에 읽는다(행마다 다시 묻지 않는다).
 * 사용자 목록은 커서, 관리자 목록은 오프셋이다 — 접수가 계속 들어오는 사용자 목록에서 오프셋은 항목이 밀린다.
 */
@Service
public class PreorderQueryService {

    private final Preorders preorders;
    private final Campaigns campaigns;
    private final SyncJobs syncJobs;
    private final SyncAttemptReader syncAttempts;
    private final PreorderQueryProperties properties;
    private final Clock clock;

    PreorderQueryService(Preorders preorders, Campaigns campaigns, SyncJobs syncJobs,
                         SyncAttemptReader syncAttempts, PreorderQueryProperties properties, Clock clock) {
        this.preorders = preorders;
        this.campaigns = campaigns;
        this.syncJobs = syncJobs;
        this.syncAttempts = syncAttempts;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 내 예약 목록(최신순). 한 건 더 읽어 다음 페이지가 있는지 본다.
     *
     * @param displayStatuses 이 화면 단계들 중 하나. 비었으면 거르지 않는다. 단계는 읽을 때의 시각으로 가른다 — 목록의 displayStatus 와 같다
     */
    public CursorPage<PreorderView.Summary> findMine(UUID customerId, PreorderStatus status,
                                                     Set<PreorderDisplayStatus> displayStatuses, UUID productId,
                                                     String cursor, int size) {
        Position position = Position.of(cursor);
        Instant now = clock.instant();
        List<PreorderSnapshot> found = new ArrayList<>(displayStatuses.isEmpty()
                ? preorders.findForCustomer(customerId, status, productId, position.createdAt(), position.id(), size + 1)
                : preorders.findForCustomerInStages(customerId, status, productId,
                        stages(EnumSet.copyOf(displayStatuses), now), position.createdAt(), position.id(), size + 1));
        String nextCursor = trimToPage(found, size);
        Map<UUID, SyncJobStatus> registerStatuses = pendingRegisterStatuses(found);
        List<PreorderView.Summary> items = withBatches(found, (preorder, batch) -> new PreorderView.Summary(
                preorder, batch, displayStatus(preorder, registerStatuses.get(preorder.id()), now)));
        return nextCursor == null ? CursorPage.last(items) : CursorPage.of(items, nextCursor);
    }

    /** 내 예약의 화면 단계별 수. 모든 단계를 단계 선언 순서로, 없는 단계는 0 으로 준다. 지금 시각으로 가르고, 문장 하나다. */
    public Map<PreorderDisplayStatus, Long> countMine(UUID customerId) {
        List<PreorderDisplayStatus> all = List.of(PreorderDisplayStatus.values());
        List<Long> counts = preorders.countForCustomerByStage(customerId, stages(all, clock.instant()));
        Map<PreorderDisplayStatus, Long> byStage = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            byStage.put(all.get(i), counts.get(i));
        }
        return byStage;
    }

    /**
     * 내 진행 중인 예약(최신순). 상품마다 많아야 한 건이라 보통 한 페이지에 다 담긴다 —
     * 넘치면 잘라 버리지 않고 다음 커서를 준다(빠진 상품은 예약하지 않은 것으로 보인다).
     */
    public CursorPage<PreorderView.Active> findMyActive(UUID customerId, String cursor) {
        Position position = Position.of(cursor);
        int size = properties.activePageSize();
        List<PreorderSnapshot> found = new ArrayList<>(preorders.findActiveForCustomer(customerId,
                position.createdAt(), position.id(), size + 1));
        String nextCursor = trimToPage(found, size);
        Map<UUID, SyncJobStatus> registerStatuses = pendingRegisterStatuses(found);
        Instant now = clock.instant();
        List<PreorderView.Active> items = found.stream().map(preorder -> new PreorderView.Active(
                preorder, displayStatus(preorder, registerStatuses.get(preorder.id()), now))).toList();
        return nextCursor == null ? CursorPage.last(items) : CursorPage.of(items, nextCursor);
    }

    /** 예약 하나. 본인과 관리자만 볼 수 있고, 남의 예약은 존재를 알리지 않는다(404). */
    public PreorderView.Summary findOne(Viewer viewer, String preorderToken) {
        PreorderSnapshot preorder = require(viewer, preorderToken);
        SyncJobStatus registerStatus = pendingRegisterStatuses(List.of(preorder)).get(preorder.id());
        return new PreorderView.Summary(preorder, campaigns.getBatch(preorder.shipmentBatchId()),
                displayStatus(preorder, registerStatus, clock.instant()));
    }

    /** 상태 전이 이력(번호 순). 접근 규칙은 상세와 같다. */
    public List<PreorderHistoryEntry> findHistory(Viewer viewer, String preorderToken) {
        return preorders.history(require(viewer, preorderToken).id());
    }

    /** @throws BusinessException VALIDATION_FAILED — 옵션 · 배송 차수를 상품 없이 물었다 */
    public OffsetPage<PreorderView.AdminSummary> findForAdmin(AdminPreorderFilter filter, int page, int size) {
        requireProductScope(filter);
        Instant now = clock.instant();
        PreorderStatus status = filter.status();
        AdminPreorderSearch.Progress progress = null;
        if (filter.displayStatus() != null) {
            PreorderStatus shown = statusOf(filter.displayStatus());
            if (status != null && status != shown) {
                return OffsetPage.of(List.of(), page, size, 0);
            }
            status = shown;
            progress = progressOf(filter.displayStatus(), now);
        }
        SyncJobStatus jobStatus = filter.registerJobStatus();
        OffsetPage<PreorderSnapshot> found = preorders.searchForAdmin(new AdminPreorderSearch(status,
                filter.customerId(), filter.productId(), filter.from(), filter.to(),
                jobStatus == null ? null : jobStatus.name(), filter.externalReference(), filter.optionId(),
                filter.batchNumber(), progress), page, size);
        Map<UUID, SyncJobStatus> registerStatuses = registerJobStatuses(found.items());
        List<PreorderView.AdminSummary> items = withBatches(found.items(), (preorder, batch) ->
                new PreorderView.AdminSummary(preorder, batch, registerStatuses.get(preorder.id()),
                        displayStatus(preorder, registerStatuses.get(preorder.id()), now)));
        return OffsetPage.of(items, page, size, found.total());
    }

    /** 옵션 · 차수 색인은 상품 id 로 시작하고, 차수 번호는 상품마다 따로 매긴다. */
    private void requireProductScope(AdminPreorderFilter filter) {
        if (filter.productId() != null) {
            return;
        }
        if (filter.optionId() != null) {
            throw ValidationFailures.of("optionId", "productId 와 함께 보내야 합니다.");
        }
        if (filter.batchNumber() != null) {
            throw ValidationFailures.of("batchNumber", "productId 와 함께 보내야 합니다.");
        }
    }

    /** 관리자 상세. 작업 · 시도 · 이력까지 함께 읽는다. */
    public PreorderView.AdminDetail findOneForAdmin(String preorderToken) {
        PreorderSnapshot preorder = preorders.getByToken(preorderToken);
        List<SyncJobSnapshot> jobs = syncJobs.findByPreorder(preorder.id());
        SyncJobStatus registerStatus = jobs.stream().filter(job -> job.jobType() == SyncJobType.REGISTER)
                .map(SyncJobSnapshot::status).findFirst().orElse(null);
        return new PreorderView.AdminDetail(preorder, campaigns.getBatch(preorder.shipmentBatchId()), jobs,
                syncAttempts.findByJobIds(jobs.stream().map(SyncJobSnapshot::id).toList()),
                preorders.history(preorder.id()), displayStatus(preorder, registerStatus, clock.instant()));
    }

    /**
     * 화면 단계. 등록 작업이 아직 시도 전(PENDING · 없음)이면 접수, 그 밖이면 처리 중이다.
     * 결제 진행 중 · 기한 지남은 표시일 뿐 판단에 쓰지 않는다.
     */
    private PreorderDisplayStatus displayStatus(PreorderSnapshot preorder, SyncJobStatus registerStatus, Instant now) {
        return switch (preorder.status()) {
            case PENDING_SYNC -> registerStatus == null || registerStatus == SyncJobStatus.PENDING
                    ? PreorderDisplayStatus.RECEIVED : PreorderDisplayStatus.PROCESSING;
            case REGISTERED -> {
                if (!now.isBefore(preorder.paymentDueAt())) {
                    yield PreorderDisplayStatus.PAYMENT_EXPIRED;
                }
                yield preorder.paymentStartedAt() == null
                        ? PreorderDisplayStatus.PAYABLE : PreorderDisplayStatus.PAYMENT_IN_PROGRESS;
            }
            case RESERVED -> PreorderDisplayStatus.RESERVED;
            case CANCELING -> PreorderDisplayStatus.CANCELING;
            case CANCELED -> PreorderDisplayStatus.CANCELED;
        };
    }

    private static List<StageCondition> stages(Collection<PreorderDisplayStatus> displayStatuses, Instant now) {
        return displayStatuses.stream()
                .map(displayStatus -> new StageCondition(statusOf(displayStatus), progressOf(displayStatus, now)))
                .toList();
    }

    /** 그 화면 단계로 보이는 원장 상태. {@link #displayStatus} 를 거꾸로 푼 것이라 함께 고친다. */
    private static PreorderStatus statusOf(PreorderDisplayStatus displayStatus) {
        return switch (displayStatus) {
            case RECEIVED, PROCESSING -> PreorderStatus.PENDING_SYNC;
            case PAYABLE, PAYMENT_IN_PROGRESS, PAYMENT_EXPIRED -> PreorderStatus.REGISTERED;
            case RESERVED -> PreorderStatus.RESERVED;
            case CANCELING -> PreorderStatus.CANCELING;
            case CANCELED -> PreorderStatus.CANCELED;
        };
    }

    /** 원장 상태가 같은 화면 단계들을 가르는 조건. 상태만으로 정해지는 단계는 null 이다. */
    private static AdminPreorderSearch.Progress progressOf(PreorderDisplayStatus displayStatus, Instant now) {
        Instant dueCutoff = now.minus(PreorderSnapshot.PAYMENT_WINDOW);
        return switch (displayStatus) {
            case RECEIVED -> AdminPreorderSearch.Progress.registerAttempted(false);
            case PROCESSING -> AdminPreorderSearch.Progress.registerAttempted(true);
            case PAYABLE -> AdminPreorderSearch.Progress.payableAfter(dueCutoff, false);
            case PAYMENT_IN_PROGRESS -> AdminPreorderSearch.Progress.payableAfter(dueCutoff, true);
            case PAYMENT_EXPIRED -> AdminPreorderSearch.Progress.payableUntil(dueCutoff);
            case RESERVED, CANCELING, CANCELED -> null;
        };
    }

    /** 한 건 더 읽은 목록을 페이지 크기로 줄인다. 다음 페이지가 있으면 그 커서를, 없으면 null 을 준다. */
    private static String trimToPage(List<PreorderSnapshot> found, int size) {
        if (found.size() <= size) {
            return null;
        }
        found.removeLast();
        PreorderSnapshot last = found.getLast();
        return Cursor.encode(last.createdAt().toString(), last.id());
    }

    private PreorderSnapshot require(Viewer viewer, String preorderToken) {
        PreorderSnapshot preorder = preorders.getByToken(preorderToken);
        if (!viewer.canSee(preorder.customerId())) {
            throw new BusinessException(PreorderErrorCode.PREORDER_NOT_FOUND);
        }
        return preorder;
    }

    private <T> List<T> withBatches(List<PreorderSnapshot> found, BatchMapper<T> mapper) {
        Map<UUID, ShipmentBatchSnapshot> byId = campaigns.findBatches(
                found.stream().map(PreorderSnapshot::shipmentBatchId).distinct().toList());
        return found.stream().map(preorder -> mapper.map(preorder, byId.get(preorder.shipmentBatchId()))).toList();
    }

    private Map<UUID, SyncJobStatus> registerJobStatuses(Collection<PreorderSnapshot> found) {
        return syncJobs.registerStatuses(found.stream().map(PreorderSnapshot::id).toList());
    }

    /** 화면 단계에 쓰는 등록 작업 상태 — {@link #displayStatus} 는 PENDING_SYNC 에서만 본다. 그런 행이 없으면 읽지 않는다. */
    private Map<UUID, SyncJobStatus> pendingRegisterStatuses(Collection<PreorderSnapshot> found) {
        return registerJobStatuses(found.stream().filter(preorder -> preorder.status() == PreorderStatus.PENDING_SYNC)
                .toList());
    }

    @FunctionalInterface
    private interface BatchMapper<T> {
        T map(PreorderSnapshot preorder, ShipmentBatchSnapshot batch);
    }
}
