package com.grandis.nova.waitingroom.control;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 제어 루프마다 마지막으로 한 바퀴를 끝낸 때. 성공 · 실패를 가리지 않는다 — Redis 장애로 실패해도 루프는 살아 있다.
 * 가장 오래 멈춘 루프로 판정해, 루프 하나만 죽어도 드러난다.
 */
@Component
class LoopBeats {

    private final LongSupplier nanoTime;
    private final Map<String, AtomicLong> beats = new ConcurrentHashMap<>();
    private volatile boolean running;

    LoopBeats() {
        this(System::nanoTime);
    }

    LoopBeats(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    void start(List<String> loops) {
        long now = nanoTime.getAsLong();
        beats.clear();
        loops.forEach(loop -> beats.put(loop, new AtomicLong(now)));
        running = true;
    }

    void stop() {
        running = false;
    }

    void beat(String loop) {
        AtomicLong beat = beats.get(loop);
        if (beat != null) {
            beat.set(nanoTime.getAsLong());
        }
    }

    boolean running() {
        return running;
    }

    /** 가장 오래 멈춘 루프가 마지막 바퀴를 끝낸 뒤 지난 시간. */
    Duration sinceOldest() {
        long now = nanoTime.getAsLong();
        return Duration.ofNanos(beats.values().stream().mapToLong(beat -> now - beat.get()).max().orElse(0));
    }
}
