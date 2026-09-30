package com.grandis.nova.preorder.deadletter;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.preorder.PreorderErrorCode;
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
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 관리자 DLQ 조회 · 되돌리기 · 버리기. */
@Service
class DeadLetterAdminService {

    /** 일괄 되돌리기 한 번의 최대 건수. */
    public static final int MAX_BATCH_SIZE = 1000;

    private static final Logger log = LoggerFactory.getLogger(DeadLetterAdminService.class);
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final DeadLetterEventRepository events;
    private final DeadLetterRedrives redrives;
    private final Preorders preorders;
    private final JsonMapper jsonMapper;
    private final TaskExecutor redriveExecutor;
    private final Clock clock;

    DeadLetterAdminService(DeadLetterEventRepository events, DeadLetterRedrives redrives, Preorders preorders,
                           JsonMapper jsonMapper,
                           @Qualifier(DeadLetterConfig.REDRIVE_EXECUTOR) TaskExecutor redriveExecutor, Clock clock) {
        this.events = events;
        this.redrives = redrives;
        this.preorders = preorders;
        this.jsonMapper = jsonMapper;
        this.redriveExecutor = redriveExecutor;
        this.clock = clock;
    }

    /** 없는 예약 id 로 거르면 빈 목록이다. */
    @Transactional(readOnly = true)
    public OffsetPage<DeadLetterView> find(DeadLetterStatus status, String eventType, FailureReason failureReason,
                                          String preorderToken, Long customerId, Instant from, Instant to,
                                          int page, int size) {
        Optional<Long> preorderId = Optional.empty();
        if (preorderToken != null) {
            preorderId = preorders.findByToken(preorderToken).map(PreorderSnapshot::id);
            if (preorderId.isEmpty()) {
                return OffsetPage.of(List.of(), page, size, 0);
            }
        }
        DeadLetterFilter filter = new DeadLetterFilter(status, eventType, failureReason, preorderId.orElse(null),
                customerId, from, to);
        Page<DeadLetterEvent> found = events.findAll(filter.toSpecification(),
                PageRequest.of(page, size, NEWEST_FIRST));
        return OffsetPage.of(views(found.getContent()), page, size, found.getTotalElements());
    }

    @Transactional(readOnly = true)
    public DeadLetterView findOne(Long id) {
        return views(List.of(find(id))).getFirst();
    }

    public DeadLetterView redrive(Long id, String requestedBy) {
        return views(List.of(redrives.redrive(id, requestedBy))).getFirst();
    }

    /** @throws BusinessException DEAD_LETTER_NOT_FOUND · DEAD_LETTER_NOT_DISCARDABLE(OPEN 이 아님) */
    @Transactional
    public DeadLetterView discard(Long id, String discardedBy, String note) {
        DeadLetterEvent event = find(id);
        if (events.discard(id, discardedBy, note, clock.instant()) != 1) {
            throw new BusinessException(PreorderErrorCode.DEAD_LETTER_NOT_DISCARDABLE,
                    Map.of("reason", "status=" + event.getStatus()));
        }
        return views(List.of(find(id))).getFirst();
    }

    /**
     * 대상을 골라 바로 돌려주고, 이 인스턴스가 초당 ratePerSecond 건씩 되돌린다(한 번에 최대 MAX_BATCH_SIZE).
     * 조건으로 고를 때 failureReason 을 비우면 PROCESSING_FAILED 만 — 되돌려도 같은 결과인 행이 앞을 막지 않게.
     * 되돌리기를 기다리지 않거나 지금 코드로도 되돌릴 수 없는 원문, 없는 id 는 건너뛴 수로 센다.
     */
    public BatchRedrive redriveBatch(List<Long> ids, String eventType, FailureReason failureReason, int ratePerSecond,
                                     String requestedBy) {
        List<Long> candidates;
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
        List<Long> targets = events.findAllById(candidates).stream()
                .filter(event -> event.waitingForRedrive(staleBefore))
                .filter(event -> DeadLetterBody.parse(jsonMapper, event.getBody()).redrivable())
                .map(DeadLetterEvent::getId)
                .sorted()
                .toList();
        if (!targets.isEmpty()) {
            redriveExecutor.execute(() -> redrivePaced(targets, ratePerSecond, requestedBy));
        }
        return new BatchRedrive(targets.size(), candidates.size() - targets.size(),
                (targets.size() + ratePerSecond - 1L) / ratePerSecond);
    }

    private void redrivePaced(List<Long> targets, int ratePerSecond, String requestedBy) {
        Duration interval = Duration.ofNanos(Duration.ofSeconds(1).toNanos() / ratePerSecond);
        for (Long id : targets) {
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

    private DeadLetterEvent find(Long id) {
        return events.findById(id).orElseThrow(() -> new BusinessException(PreorderErrorCode.DEAD_LETTER_NOT_FOUND));
    }

    private Instant staleBefore() {
        return clock.instant().minus(DeadLetterRedrives.STALE_REDRIVE);
    }

    private List<DeadLetterView> views(List<DeadLetterEvent> found) {
        Instant staleBefore = staleBefore();
        Map<Long, PreorderSnapshot> owners = preorders.findAllById(found.stream()
                .map(DeadLetterEvent::getPreorderId).filter(Objects::nonNull).distinct().toList());
        return found.stream()
                .map(event -> new DeadLetterView(event,
                        event.getPreorderId() == null ? null : owners.get(event.getPreorderId()).preorderToken(),
                        event.waitingForRedrive(staleBefore)
                                && DeadLetterBody.parse(jsonMapper, event.getBody()).redrivable()))
                .toList();
    }
}
