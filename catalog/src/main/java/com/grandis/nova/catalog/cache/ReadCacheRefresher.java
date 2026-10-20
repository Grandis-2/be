package com.grandis.nova.catalog.cache;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 읽기 캐시의 미리 갱신을 맡는 실행기 — 메모리 · Redis 캐시가 같이 쓴다. 크기를 묶는다: 동시에 도는 갱신은 {@link #THREADS} 개, 기다리는 갱신은
 * {@link #QUEUE} 개까지이고 넘치면 맡기지 않는다(그 키는 만료 때 요청이 직접 읽는다). 갱신 하나가 DB 연결 하나를 쓰므로 풀(10)을 다 가져가지 않게
 * 작게 둔다. 요청 스레드와 따로라 갱신이 늦어도 요청은 들고 있던 값으로 바로 답한다.
 */
@Component
public class ReadCacheRefresher implements Executor {

    static final int THREADS = 2;
    static final int QUEUE = 64;
    /** 내려갈 때 갱신이 끝나기를 기다리는 시간. */
    static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(2);

    private final ThreadPoolExecutor executor;

    public ReadCacheRefresher() {
        AtomicInteger seq = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(THREADS, THREADS, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE), runnable -> {
            Thread thread = new Thread(runnable, "read-cache-refresh-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    /** @throws java.util.concurrent.RejectedExecutionException 실행기가 찼다(또는 내려가는 중) — 캐시는 맡기지 않고 지나간다 */
    @Override
    public void execute(Runnable command) {
        executor.execute(command);
    }

    /** 새 갱신은 받지 않고, 큐에 있거나 도는 갱신이 끝나기를 {@link #SHUTDOWN_WAIT} 까지 기다린다. 넘으면 끊는다(큐에 남은 것은 버린다). */
    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
