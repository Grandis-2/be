package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.config.WorkerProperties;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.time.Duration;

/** 시험이 빨리 끝나게 시간을 줄인 운영값. */
final class Settings {

    static final Duration SLOW_CALL = Duration.ofMillis(150);

    private Settings() {
    }

    /** 연결 · 응답 0.3초. */
    static WorkerProperties.Mock mock(URI baseUrl) {
        return new WorkerProperties.Mock(baseUrl, Duration.ofSeconds(30), Duration.ofSeconds(1), DataSize.ofKilobytes(64),
                Duration.ofMillis(300), Duration.ofMillis(300), SLOW_CALL);
    }

    /** 최근 4건 중 절반이 실패하면 열리고, 0.2초(다시 열리면 0.4초) 뒤 반쯤 열려 3건을 시험한다. */
    static WorkerProperties.Circuit circuit() {
        return new WorkerProperties.Circuit(4, 4, 50, Duration.ofMillis(200), Duration.ofMillis(400), 2, 3);
    }

    /** 상한 6 · 시작 3 · 최소 2 · CANCEL 전용 1, 10초 창. */
    static WorkerProperties.Concurrency concurrency() {
        return new WorkerProperties.Concurrency(6, 1, 3, 2, 1, Duration.ofMillis(100), 40, Duration.ofSeconds(10),
                Duration.ofSeconds(1), 5, 20, 1, 0.5);
    }

    static WorkerProperties.RejectGuard rejectGuard() {
        return new WorkerProperties.RejectGuard(5, Duration.ofMinutes(1), Duration.ofMinutes(3));
    }
}
