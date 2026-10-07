package com.grandis.nova.preorder.deadletter.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterEvent;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterEventRepository;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterStatus;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterSummary;
import com.grandis.nova.preorder.deadletter.domain.FailureReason;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.Preorders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 관리자 DLQ 조회 · 되돌리기 · 버리기. */
@Service
public class DeadLetterAdminService {

    /** 일괄 되돌리기 한 번의 최대 건수. */
    public static final int MAX_BATCH_SIZE = 1000;

    private static final Logger log = LoggerFactory.getLogger(DeadLetterAdminService.class);
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final DeadLetterEventRepository events;
    private final DeadLetterRedrives redrives;
    private final Preorders preorders;
    private final DeadLetterBodyParser bodyParser;
    private final TaskExecutor redriveExecutor;
    private final Clock clock;

    DeadLetterAdminService(DeadLetterEventRepository events, DeadLetterRedrives redrives, Preorders preorders,
                           DeadLetterBodyParser bodyParser,
                           @Qualifier(DeadLetterConfig.REDRIVE_EXECUTOR) TaskExecutor redriveExecutor, Clock clock) {
        this.events = events;
        this.redrives = redrives;
        this.preorders = preorders;
        this.bodyParser = bodyParser;
        this.redriveExecutor = redriveExecutor;
        this.clock = clock;
    }

    /** 없는 예약 id 로 거르면 빈 목록이다. */
    @Transactional(readOnly = true)
    public OffsetPage<DeadLetterListItem> find(DeadLetterStatus status, String eventType, FailureReason failureReason,
                                          String preorderToken, UUID customerId, Instant from, Instant to,
                                          int page, int size) {
        Optional<UUID> preorderId = Optional.empty();
        if (preorderToken != null) {
            preorderId = preorders.findByToken(preorderToken).map(PreorderSnapshot::id);
            if (preorderId.isEmpty()) {
                return OffsetPage.of(List.of(), page, size, 0);
            }
        }
        Page<DeadLetterSummary> found = events.search(status, eventType, failureReason, preorderId.orElse(null),
                customerId, from, to, PageRequest.of(page, size, NEWEST_FIRST));
        return OffsetPage.of(listItems(found.getContent()), page, size, found.getTotalElements());
    }

    /** 한 행이라 원문을 다시 읽어 지금 코드로 되돌릴 수 있는지 정확히 가른다. */
    @Transactional(readOnly = true)
    public DeadLetterView findOne(UUID id) {
        return view(find(id));
    }

    public DeadLetterView redrive(UUID id, String requestedBy) {
        return view(redrives.redrive(id, requestedBy));
    }

    /** @throws BusinessException DEAD_LETTER_NOT_FOUND · DEAD_LETTER_NOT_DISCARDABLE(OPEN 이 아님) */
    @Transactional
    public DeadLetterView discard(UUID id, String discardedBy, String note) {
        DeadLetterEvent event = find(id);
        if (events.discard(id, discardedBy, note, clock.instant()) != 1) {
            throw new BusinessException(PreorderErrorCode.DEAD_LETTER_NOT_DISCARDABLE,
                    Map.of("reason", "status=" + event.getStatus()));
        }
        return view(find(id));
    }

    /**
     * 대상을 골라 바로 돌려주고, 이 인스턴스가 초당 ratePerSecond 건씩 되돌린다(한 번에 최대 MAX_BATCH_SIZE).
     * 조건으로 고를 때 failureReason 을 비우면 PROCESSING_FAILED 만 — 되돌려도 같은 결과인 행이 앞을 막지 않게.
     * 되돌리기를 기다리지 않거나 저장된 분류로 되돌릴 수 없는 행, 없는 id 는 건너뛴 수로 센다. 원문은 보낼 때 한 건씩 다시 읽는다.
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
        if (!targets.isEmpty()) {
            redriveExecutor.execute(() -> redrivePaced(targets, ratePerSecond, requestedBy));
        }
        return new BatchRedrive(targets.size(), candidates.size() - targets.size(),
                (targets.size() + ratePerSecond - 1L) / ratePerSecond);
    }

    private void redrivePaced(List<UUID> targets, int ratePerSecond, String requestedBy) {
        Duration interval = Duration.ofNanos(Duration.ofSeconds(1).toNanos() / ratePerSecond);
        for (UUID id : targets) {
            try {
                redrives.redrive(id, requestedBy);
            } catch (BusinessException e) {
                log.info("되돌리기 대상에서 빠졌다(그 사이 상태가 바뀌었거나 보내지 못함) deadLetterId={} code={}",
                        id, e.errorCode());
            } catch (RuntimeException e) {
                log.warn("되돌리지 못했다 — 같은 요청을 다시 보내면 이어진다 deadLetterId={}", id, e);
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("일괄 되돌리기가 중단됐다 — 같은 요청을 다시 보내면 이어진다");
                return;
            }
        }
    }

    private DeadLetterEvent find(UUID id) {
        return events.findById(id).orElseThrow(() -> new BusinessException(PreorderErrorCode.DEAD_LETTER_NOT_FOUND));
    }

    private Instant staleBefore() {
        return clock.instant().minus(DeadLetterStatus.STALE_REDRIVE);
    }

    private boolean redrivable(DeadLetterSummary summary, Instant staleBefore) {
        return summary.getStatus().waitingForRedrive(summary.getRedriveStartedAt(), staleBefore)
                && summary.getFailureReason().redrivableFor(summary.getEventType());
    }

    private List<DeadLetterListItem> listItems(List<DeadLetterSummary> found) {
        Instant staleBefore = staleBefore();
        Map<UUID, PreorderSnapshot> owners = preorders.findAllById(found.stream()
                .map(DeadLetterSummary::getPreorderId).filter(Objects::nonNull).distinct().toList());
        return found.stream()
                .map(summary -> new DeadLetterListItem(summary, tokenOf(summary.getPreorderId(), owners),
                        redrivable(summary, staleBefore)))
                .toList();
    }

    private DeadLetterView view(DeadLetterEvent event) {
        Map<UUID, PreorderSnapshot> owners = event.getPreorderId() == null ? Map.of()
                : preorders.findAllById(List.of(event.getPreorderId()));
        return new DeadLetterView(event, tokenOf(event.getPreorderId(), owners),
                event.waitingForRedrive(staleBefore()) && bodyParser.parse(event.getBody()).redrivable());
    }

    private String tokenOf(UUID preorderId, Map<UUID, PreorderSnapshot> owners) {
        return preorderId == null ? null : owners.get(preorderId).preorderToken();
    }
}
