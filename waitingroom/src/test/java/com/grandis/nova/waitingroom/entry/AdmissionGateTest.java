package com.grandis.nova.waitingroom.entry;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.waitingroom.control.AdmissionProperties;
import com.grandis.nova.waitingroom.control.ControlPlaneProperties;
import com.grandis.nova.waitingroom.control.IdlePassCounter;
import com.grandis.nova.waitingroom.control.RedisClock;
import com.grandis.nova.waitingroom.control.SnapshotHolder;
import com.grandis.nova.waitingroom.control.TestSnapshots;
import com.grandis.nova.waitingroom.domain.queue.AdmissionTicket;
import com.grandis.nova.waitingroom.domain.queue.QueueToken;
import com.grandis.nova.waitingroom.redis.LuaScripts;
import com.grandis.nova.waitingroom.redis.QueueStore;
import com.grandis.nova.waitingroom.support.TestJwts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.util.List;

/** 기동 직후처럼 판정 재료를 한 번도 받지 못한 노드는 통과시키지 않는다. */
class AdmissionGateTest {

    private final SnapshotHolder empty = TestSnapshots.emptyHolder();
    private final RedisClock clock = new RedisClock(Clock.systemUTC());
    private final QueueStore queue = new QueueStore(new LuaScripts(null));
    private final QueueToken queueTokens = QueueToken.of(TestJwts.TOKEN_SECRET, List.of(), null);
    private final ProductClosures closures = new ProductClosures();
    private final EntryMetrics metrics = new EntryMetrics(new SimpleMeterRegistry());
    private final AdmissionGate gate = new AdmissionGate(empty, clock, new IdlePassCounter(), queue,
            AdmissionTicket.of(TestJwts.TOKEN_SECRET, List.of(), null), queueTokens, closures, metrics,
            new AdmissionProperties(null, null), new ControlPlaneProperties(null, null, null, null, null, null, null, null));

    @Test
    void 판정_재료가_없으면_진입도_조회도_503_이다() {
        QueueLookup lookup = new QueueLookup(empty, clock, queue, queueTokens, closures, gate, metrics);

        StepVerifier.create(gate.enter("101", "1"))
                .expectErrorMatches(e -> e instanceof BusinessException business
                        && business.errorCode() == CommonErrorCode.DEPENDENCY_UNAVAILABLE)
                .verify();
        StepVerifier.create(lookup.status("101", "1", "qt_x"))
                .expectErrorMatches(e -> e instanceof BusinessException business
                        && business.errorCode() == CommonErrorCode.DEPENDENCY_UNAVAILABLE)
                .verify();
    }
}
