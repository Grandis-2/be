package com.grandis.nova.preorder.deadletter.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.deadletter.DeadLetterRedriver;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterBody;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterEvent;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterEventRepository;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * 원문을 원래 큐로 되돌린다. 선점(OPEN → REDRIVING) → 트랜잭션 밖에서 보내기 → 보냄 기록(REDRIVEN) 순이라
 * 두 관리자가 동시에 눌러도 한 번만 보낸다. 보내기 실패는 실제로 갔는지 알 수 없어 REDRIVING 에 두고, 멈춘 기준(STALE_REDRIVE) 뒤 다시 선점한다.
 */
@Component
class DeadLetterRedrives {


    private static final Logger log = LoggerFactory.getLogger(DeadLetterRedrives.class);

    private final DeadLetterEventRepository events;
    private final ObjectProvider<DeadLetterRedriver> redriver;
    private final TransactionTemplate transactionTemplate;
    private final DeadLetterBodyParser bodyParser;
    private final Clock clock;

    DeadLetterRedrives(DeadLetterEventRepository events, ObjectProvider<DeadLetterRedriver> redriver,
                       TransactionTemplate transactionTemplate, DeadLetterBodyParser bodyParser, Clock clock) {
        this.events = events;
        this.redriver = redriver;
        this.transactionTemplate = transactionTemplate;
        this.bodyParser = bodyParser;
        this.clock = clock;
    }

    /**
     * @throws BusinessException DEAD_LETTER_NOT_FOUND · DEAD_LETTER_NOT_REDRIVABLE(지금 코드로도 읽지 못하는 원문,
     *                           되돌리기를 기다리는 상태가 아님) · DEPENDENCY_UNAVAILABLE(보내지 못함 — STALE_REDRIVE 뒤 다시)
     */
    DeadLetterEvent redrive(Long id, String requestedBy) {
        DeadLetterEvent event = events.findById(id)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.DEAD_LETTER_NOT_FOUND));
        DeadLetterBody parsed = bodyParser.parse(event.getBody());
        if (!parsed.redrivable()) {
            throw notRedrivable("failureReason=" + parsed.failureReason());
        }
        DeadLetterRedriver sender = redriver.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("큐 연동(nova.sqs)이 설정되지 않아 되돌릴 수 없다");
        }
        Instant startedAt = clock.instant();
        Integer claimed = transactionTemplate.execute(status ->
                events.claimRedrive(id, requestedBy, startedAt, startedAt.minus(DeadLetterStatus.STALE_REDRIVE)));
        if (claimed == null || claimed != 1) {
            throw notRedrivable("status=" + currentStatus(id));
        }
        try {
            sender.redrive(event.getSourceQueue(), event.getBody(), id);
        } catch (RuntimeException e) {
            // 시간 초과는 큐가 이미 받은 뒤일 수 있다. OPEN 으로 돌리면 실제로 간 메시지의 처리 결과를 남길 곳이 없어진다
            log.warn("DLQ 메시지를 되돌리지 못했다 — {} 뒤 다시 되돌릴 수 있다 deadLetterId={}", DeadLetterStatus.STALE_REDRIVE, id, e);
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        Integer recorded = transactionTemplate.execute(status -> events.markRedriven(id, startedAt, clock.instant()));
        if (recorded == null || recorded != 1) {
            log.info("보냄 기록 전에 처리 결과가 먼저 남았다 deadLetterId={} status={}", id, currentStatus(id));
        }
        return events.findById(id).orElseThrow();
    }

    /** 원문까지 올리지 않도록 상태만 읽는다. */
    private DeadLetterStatus currentStatus(Long id) {
        return events.findStatusById(id).orElse(null);
    }

    private BusinessException notRedrivable(String reason) {
        return new BusinessException(PreorderErrorCode.DEAD_LETTER_NOT_REDRIVABLE, Map.of("reason", reason));
    }
}
