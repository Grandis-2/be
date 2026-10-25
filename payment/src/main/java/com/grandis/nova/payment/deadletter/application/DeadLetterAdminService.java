package com.grandis.nova.payment.deadletter.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.payment.PaymentErrorCode;
import com.grandis.nova.payment.deadletter.application.RedriveBatchStore.StoredBatch;
import com.grandis.nova.payment.deadletter.domain.DeadLetterEvent;
import com.grandis.nova.payment.deadletter.domain.DeadLetterEventRepository;
import com.grandis.nova.payment.deadletter.domain.DeadLetterStatus;
import com.grandis.nova.payment.deadletter.domain.DeadLetterSummary;
import com.grandis.nova.payment.deadletter.domain.FailureReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** 관리자 DLQ 조회 · 되돌리기 · 버리기. 관리자 화면은 order 를 거쳐 온다(내부 API). */
@Service
public class DeadLetterAdminService {

    /** 일괄 되돌리기 한 번의 최대 건수. */
    public static final int MAX_BATCH_SIZE = 1000;

    private static final Logger log = LoggerFactory.getLogger(DeadLetterAdminService.class);
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final DeadLetterEventRepository events;
    private final DeadLetterRedrives redrives;
    private final DeadLetterBodyParser bodyParser;
    private final DeadLetterDiscards discards;
    private final TaskExecutor redriveExecutor;
    private final RedriveBatchStore batches;
    private final Clock clock;

    DeadLetterAdminService(DeadLetterEventRepository events, DeadLetterRedrives redrives,
                           DeadLetterBodyParser bodyParser, DeadLetterDiscards discards,
                           @Qualifier(DeadLetterConfig.REDRIVE_EXECUTOR) TaskExecutor redriveExecutor,
                           RedriveBatchStore batches, Clock clock) {
        this.events = events;
        this.redrives = redrives;
        this.bodyParser = bodyParser;
        this.discards = discards;
        this.redriveExecutor = redriveExecutor;
        this.batches = batches;
        this.clock = clock;
    }

    /** @param orderId 주문 이벤트(aggregate ORDER)의 주문 id 로 거른다 */
    @Transactional(readOnly = true)
    public OffsetPage<DeadLetterListItem> find(DeadLetterStatus status, String eventType, FailureReason failureReason,
                                               UUID orderId, Instant from, Instant to, int page, int size) {
        Page<DeadLetterSummary> found = events.search(status, eventType, failureReason, orderId, from, to,
                PageRequest.of(page, size, NEWEST_FIRST));
        Instant staleBefore = staleBefore();
        return OffsetPage.of(found.getContent().stream()
                .map(summary -> new DeadLetterListItem(summary, redrivable(summary, staleBefore)))
                .toList(), page, size, found.getTotalElements());
    }

    /** 한 행이라 원문을 다시 읽어 지금 코드로 되돌릴 수 있는지 정확히 가른다. */
    @Transactional(readOnly = true)
    public DeadLetterView findOne(UUID id) {
        return view(find(id));
    }

    public DeadLetterView redrive(UUID id, String requestedBy) {
        return view(redrives.redrive(id, requestedBy));
    }

    /**
     * 버린 메시지는 다시 오지 않는다 — 환불 요청이면 order 에 다시 보내는 길이 없어 그 주문은 취소 중에 머물고, 사람이 직접 환불을
     * 확인해야 한다. 그래서 커밋된 뒤 ERROR 로그 · 카운터로 드러낸다({@link DeadLetterDiscards}).
     *
     * @throws BusinessException DEAD_LETTER_NOT_FOUND · DEAD_LETTER_NOT_DISCARDABLE(OPEN 이 아님)
     */
    @Transactional
    public DeadLetterView discard(UUID id, String discardedBy, String note) {
        DeadLetterEvent event = find(id);
        if (events.discard(id, discardedBy, note, clock.instant()) != 1) {
            throw new BusinessException(PaymentErrorCode.DEAD_LETTER_NOT_DISCARDABLE,
                    Map.of("reason", "status=" + event.getStatus()));
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                discards.discarded(event, discardedBy, note);
            }
        });
        return view(find(id));
    }

    /**
     * 대상을 골라 바로 돌려주고, 이 인스턴스가 초당 ratePerSecond 건씩 되돌린다(한 번에 최대 MAX_BATCH_SIZE).
     * 조건으로 고를 때 failureReason 을 비우면 PROCESSING_FAILED 만 — 되돌려도 같은 결과인 행이 앞을 막지 않게.
     * 되돌리기를 기다리지 않거나 저장된 분류로 되돌릴 수 없는 행, 없는 id 는 건너뛴 수로 센다. 원문은 보낼 때 한 건씩 다시 읽는다.
     * 진행은 batchId 로 {@link #findBatch} 에서 본다.
     */
    public BatchRedrive redriveBatch(List<UUID> ids, String eventType, FailureReason failureReason, int ratePerSecond,
                                     String requestedBy) {
        List<UUID> candidates;
        if (ids == null || ids.isEmpty()) {
            FailureReason reason = failureReason == null ? FailureReason.PROCESSING_FAILED : failureReason;
            candidates = events.findWaitingIds(eventType, reason, staleBefore(), MAX_BATCH_SIZE);
        } else {
            candidates = ids.stream().distinct().toList();
            if (candidates.size() > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException("한 번에 " + MAX_BATCH_SIZE + " 건까지다: " + candidates.size());
            }
        }
        Instant staleBefore = staleBefore();
        List<UUID> targets = events.findSummaries(candidates).stream()
                .filter(summary -> redrivable(summary, staleBefore))
                .sorted(Comparator.comparing(DeadLetterSummary::getCreatedAt).thenComparing(DeadLetterSummary::getId))
                .map(DeadLetterSummary::getId)
                .toList();
        int skipped = candidates.size() - targets.size();
        UUID batchId = UUID.randomUUID();
        batches.start(batchId, targets, skipped, clock.instant());
        if (!targets.isEmpty()) {
            redriveExecutor.execute(() -> redrivePaced(batchId, targets, ratePerSecond, requestedBy));
        }
        return new BatchRedrive(batchId, targets.size(), skipped,
                (targets.size() + ratePerSecond - 1L) / ratePerSecond);
    }

    /**
     * 일괄 되돌리기 진행. 결과 수는 큐로 보낸 원래 행의 지금 상태로 센다 — 보내지 못한 행의 상태와, 다시 실패해 새로 쌓인 행은 세지 않는다.
     * 결제 DLQ 는 보낸 행을 버리는 길이 없어(버리기는 OPEN 만) canceled 는 늘 0 이다.
     *
     * @throws BusinessException REDRIVE_BATCH_NOT_FOUND — 없거나 보관 시간이 지났거나, 시작 때 진행 기록을 남기지 못했다(보내기는 이어진다)
     */
    @Transactional(readOnly = true)
    public BatchRedriveProgress findBatch(UUID batchId) {
        StoredBatch stored = batches.find(batchId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.REDRIVE_BATCH_NOT_FOUND));
        Map<DeadLetterStatus, Long> byStatus = stored.sent().isEmpty() ? Map.of()
                : events.findSummaries(stored.sent()).stream()
                        .collect(Collectors.groupingBy(DeadLetterSummary::getStatus, Collectors.counting()));
        int succeeded = byStatus.getOrDefault(DeadLetterStatus.SUCCEEDED, 0L).intValue();
        int failed = byStatus.getOrDefault(DeadLetterStatus.REDRIVE_FAILED, 0L).intValue();
        int canceled = 0;
        int unsent = stored.attempted() - stored.sent().size();
        return new BatchRedriveProgress(batchId, stored.targets().size(), stored.skipped(), stored.sent().size(),
                unsent, succeeded, failed, canceled, stored.targets().size() - unsent - succeeded - failed - canceled,
                stored.startedAt(), stored.lastProgressAt(), stored.finishedAt());
    }

    private void redrivePaced(UUID batchId, List<UUID> targets, int ratePerSecond, String requestedBy) {
        Duration interval = Duration.ofNanos(Duration.ofSeconds(1).toNanos() / ratePerSecond);
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0 && !pause(interval, batchId)) {
                return;
            }
            UUID id = targets.get(i);
            UUID sent = null;
            try {
                redrives.redrive(id, requestedBy);
                sent = id;
            } catch (BusinessException e) {
                log.info("되돌리기 대상에서 빠졌다(그 사이 상태가 바뀌었거나 보내지 못함) deadLetterId={} code={}",
                        id, e.errorCode());
            } catch (RuntimeException e) {
                log.warn("되돌리지 못했다 — 같은 요청을 다시 보내면 이어진다 deadLetterId={}", id, e);
            }
            batches.progress(batchId, sent, clock.instant());
        }
        batches.finish(batchId, clock.instant());
    }

    /** 대상 사이에만 쉰다 — 마지막 대상 뒤에 쉬다 중단돼 끝 시각을 놓치지 않게. */
    private static boolean pause(Duration interval, UUID batchId) {
        try {
            Thread.sleep(interval);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("일괄 되돌리기가 중단됐다 — 같은 요청을 다시 보내면 이어진다 batchId={}", batchId);
            return false;
        }
    }

    private DeadLetterEvent find(UUID id) {
        return events.findById(id).orElseThrow(() -> new BusinessException(PaymentErrorCode.DEAD_LETTER_NOT_FOUND));
    }

    private Instant staleBefore() {
        return clock.instant().minus(DeadLetterStatus.STALE_REDRIVE);
    }

    private boolean redrivable(DeadLetterSummary summary, Instant staleBefore) {
        return summary.getStatus().waitingForRedrive(summary.getRedriveStartedAt(), staleBefore)
                && summary.getFailureReason().redrivableFor(summary.getEventType());
    }

    private DeadLetterView view(DeadLetterEvent event) {
        return new DeadLetterView(event, event.waitingForRedrive(staleBefore())
                && bodyParser.parse(event.getBody()).redrivable());
    }
}
