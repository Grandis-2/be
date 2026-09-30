package com.grandis.nova.preorder.deadletter;

import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.Preorders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Optional;

/** DLQ 적재와 되돌린 메시지의 결과 기록(모듈 공개 API). 큐 소비자가 부른다. */
@Service
public class DeadLetters {

    private static final Logger log = LoggerFactory.getLogger(DeadLetters.class);

    private final DeadLetterEventRepository events;
    private final Preorders preorders;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    DeadLetters(DeadLetterEventRepository events, Preorders preorders, JsonMapper jsonMapper, Clock clock) {
        this.events = events;
        this.preorders = preorders;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * DLQ 메시지를 쌓는다. 이미 쌓인 메시지(지우기 전에 죽어 다시 받음)면 건너뛴다. 되돌렸던 메시지가 또 온 것이면
     * 앞선 행을 REDRIVE_FAILED 로 바꾸고 새 행이 가리키게 한다. 커밋된 뒤에만 DLQ 에서 지워야 한다.
     *
     * @return 새로 쌓았으면 true
     */
    @Transactional
    public boolean record(IncomingDeadLetter incoming) {
        if (events.existsBySourceQueueAndMessageId(incoming.sourceQueue(), incoming.messageId())) {
            return false;
        }
        DeadLetterBody parsed = DeadLetterBody.parse(jsonMapper, incoming.body());
        Optional<PreorderSnapshot> preorder = Optional.ofNullable(parsed.preorderToken())
                .flatMap(preorders::findByToken);
        IncomingDeadLetter linked = linkPrevious(incoming);
        events.save(new DeadLetterEvent(linked, parsed, preorder.map(PreorderSnapshot::id).orElse(null),
                preorder.map(PreorderSnapshot::customerId).orElse(null)));
        return true;
    }

    /** 되돌린 메시지가 처리됐다. 이미 결과가 났거나 없는 행이면 바꾸지 않는다. */
    @Transactional
    public void markRedriveSucceeded(Long deadLetterId) {
        if (events.markOutcome(deadLetterId, DeadLetterStatus.SUCCEEDED, clock.instant()) != 1) {
            log.info("되돌린 메시지의 처리 결과를 남기지 않았다(이미 결과가 있거나 없는 행) deadLetterId={}", deadLetterId);
        }
    }

    /** 앞선 행이 없으면(속성이 잘못 실림) 잇지 않는다 — FK 에 걸려 적재가 계속 실패하지 않게. */
    private IncomingDeadLetter linkPrevious(IncomingDeadLetter incoming) {
        Long previous = incoming.redrivenFromId();
        if (previous == null) {
            return incoming;
        }
        if (!events.existsById(previous)) {
            log.warn("되돌린 메시지의 앞선 행이 없다 — 잇지 않고 쌓는다 deadLetterId={}", previous);
            return withPrevious(incoming, null);
        }
        if (events.markOutcome(previous, DeadLetterStatus.REDRIVE_FAILED, clock.instant()) != 1) {
            log.info("앞선 행의 결과를 바꾸지 않았다(이미 결과가 있음) deadLetterId={}", previous);
        }
        return incoming;
    }

    private static IncomingDeadLetter withPrevious(IncomingDeadLetter incoming, Long previous) {
        return new IncomingDeadLetter(incoming.sourceQueue(), incoming.messageId(), incoming.body(),
                incoming.receiveCount(), incoming.sentAt(), previous);
    }
}
