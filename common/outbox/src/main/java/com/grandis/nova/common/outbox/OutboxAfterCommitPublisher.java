package com.grandis.nova.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 커밋된 행을 유한 버퍼에 넣고 바로 돌아간다. 롤백된 메시지는 오지 않는다.
 * 발행 스레드 몇 개가 버퍼에서 있는 만큼(전송 묶음 크기까지) 꺼내 목적지별로 묶어 보낸다 — 모일 때까지 기다리지 않는다.
 * 발행 스레드 수가 발행이 동시에 쥐는 DB 커넥션 상한이다. 버퍼가 차면 그 행은 릴레이가 보낸다.
 *
 * 버퍼는 사본이다 — 행은 이미 커밋돼 있어 죽거나 멈추면 미발행으로 남고 릴레이가 보낸다.
 * 버퍼에서 릴레이가 가져갈 나이의 절반을 넘게 기다린 행은 보내지 않는다 — 다시 읽지 않으므로, 릴레이가 이미 보냈을 수 있는 행을
 * 또 보내지 않게 그 몫으로 넘긴다(절반은 인스턴스 사이 시계 차 · 전송 시간 여유).
 */
class OutboxAfterCommitPublisher implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OutboxAfterCommitPublisher.class);
    /** 종료 시 진행 중인 발행을 기다리는 시간. */
    private static final long TERMINATION_TIMEOUT_MILLIS = 5_000;
    /** 버퍼가 빈 동안 멈춤 표식을 다시 보는 간격. 인터럽트로 깨우면 보내던 SQS 호출까지 끊긴다. */
    private static final long POLL_MILLIS = 200;

    private final OutboxPublisher publisher;
    private final BlockingQueue<OutboxRow> buffer;
    private final int concurrency;
    private final Duration staleAfter;
    private final Clock clock;
    private final List<Thread> workers = new ArrayList<>();
    /** 필드 값이 아닌 객체로 둔다 — 시험이 이 빈을 감싸(spy) 복사해도 발행 스레드와 같은 표식을 본다. */
    private final AtomicBoolean stopped = new AtomicBoolean();

    OutboxAfterCommitPublisher(OutboxPublisher publisher, int concurrency, int bufferSize, Duration relayAfter,
                               Clock clock) {
        this.publisher = publisher;
        this.concurrency = concurrency;
        this.staleAfter = relayAfter.dividedBy(2);
        this.clock = clock;
        this.buffer = new ArrayBlockingQueue<>(bufferSize);
    }

    @Override
    public void afterPropertiesSet() {
        for (int i = 0; i < concurrency; i++) {
            workers.add(Thread.ofVirtual().name("outbox-publish-" + i).start(this::drain));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onAppended(OutboxAppended appended) {
        if (stopped.get() || !buffer.offer(appended.row())) {
            log.warn("발행 버퍼가 가득 차 릴레이에 맡긴다 outboxEventId={}", appended.row().id());
        }
    }

    /**
     * 멈추면 보내던 묶음만 마친다 — 끊으면 이미 닿은 묶음이 실패로 남아 다시 간다. 기다려도 안 끝난 스레드만 끊는다.
     * 버퍼에 남은 행은 미발행 그대로 릴레이 몫이다.
     */
    @Override
    public void destroy() throws InterruptedException {
        stopped.set(true);
        long deadline = System.currentTimeMillis() + TERMINATION_TIMEOUT_MILLIS;
        for (Thread worker : workers) {
            worker.join(Math.max(1, deadline - System.currentTimeMillis()));
        }
        workers.stream().filter(Thread::isAlive).forEach(Thread::interrupt);
        if (!buffer.isEmpty()) {
            log.info("멈추며 보내지 않은 {}건은 릴레이가 보낸다", buffer.size());
        }
    }

    private void drain() {
        while (!stopped.get()) {
            List<OutboxRow> rows = new ArrayList<>();
            try {
                OutboxRow first = buffer.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (first == null || stopped.get()) {
                    continue;
                }
                rows.add(first);
            } catch (InterruptedException e) {
                return;
            }
            buffer.drainTo(rows, publisher.batchSize() - 1);
            Instant staleBefore = clock.instant().minus(staleAfter);
            List<OutboxRow> fresh = rows.stream().filter(row -> row.createdAt().isAfter(staleBefore)).toList();
            if (fresh.size() < rows.size()) {
                log.warn("발행 버퍼에서 오래 기다린 {}건은 릴레이에 맡긴다", rows.size() - fresh.size());
            }
            if (!fresh.isEmpty()) {
                publishQuietly(fresh);
            }
        }
    }

    /** 발행 스레드에서 새는 예외를 로그로 남긴다. 행은 미발행으로 남는다. */
    private void publishQuietly(List<OutboxRow> rows) {
        try {
            publisher.publishAll(rows, null);
        } catch (RuntimeException e) {
            log.warn("커밋 직후 발행 중 오류 — 릴레이에 맡긴다 outboxEventIds={}",
                    rows.stream().map(OutboxRow::id).toList(), e);
        }
    }
}
