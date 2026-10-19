package com.grandis.nova.common.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 기동 중에 Redis 공유 연결을 열고 PING 을 한 번 보낸다. Spring Data Redis 는 공유 연결을 첫 명령에서 여는데, 새 JVM 의 첫 TLS 연결은
 * 요청 제한 시간(connect-timeout · timeout, 수백 ms)을 넘길 만큼 느려 그 첫 요청이 "Unable to connect to Redis" 로 실패했다.
 * 여기서 연 연결을 요청이 그대로 쓴다(shareNativeConnection 기본값).
 *
 * <ul>
 *   <li><b>시점</b>: 모든 싱글턴을 만든 직후 메인 스레드에서 끝날 때까지 기다린다 — 수명주기 시작(웹 서버가 포트를 여는
 *       WebServerStartStopLifecycle · 큐 소비기)보다 앞이다. ApplicationReadyEvent 는 톰캣이 이미 요청을 받은 뒤라 쓰지 않는다.
 *       연결 팩토리는 빈을 만들 때 이미 시작돼 있다(LettuceConnectionFactory 의 earlyStartup 기본값).</li>
 *   <li><b>시간</b>: 팩토리가 하나라 시도 하나는 요청과 같은 제한 시간을 따른다. 첫 시도가 넘겨도 그 사이 실린 클래스로 다음 시도는
 *       빨라질 수 있다(실측은 아니다 — 완료 로그의 attempts · retriedAfter 로 본다). 최대 {@link #MAX_ATTEMPTS}번, 합쳐 {@link #BUDGET} —
 *       넘으면 기다리기를 그만두고 새 시도를 하지 않는다. 제한 시간 설정이 없어도 기동은 그 이상 기다리지 않는다(진행 중이던 연결은 뒤에 남는다).</li>
 *   <li><b>실패</b>: WARN 하나만 남기고 기동은 이어 간다. 준비 상태(readiness)에도 넣지 않는다 — Redis 장애가 배포 · 확장을 막지 않게.
 *       그때는 예열이 없던 때처럼 첫 요청이 다시 연결한다.</li>
 * </ul>
 * 팩토리의 setEagerInitialization 은 쓰지 않는다 — start() 안에서 한 번만 연결하고, 실패하면 그 예외로 기동이 실패한다.
 */
final class RedisWarmup implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(RedisWarmup.class);

    /** 예열 전체에 기동이 기다리는 상한. */
    static final Duration BUDGET = Duration.ofSeconds(3);
    /** 연결 · PING 시도 횟수 상한. 연결 거부처럼 곧바로 실패하는 경우에도 이만큼만 다시 한다. */
    static final int MAX_ATTEMPTS = 3;

    private final RedisConnectionFactory connections;
    private final Duration budget;

    RedisWarmup(RedisConnectionFactory connections, Duration budget) {
        this.connections = connections;
        this.budget = budget;
    }

    @Override
    public void afterSingletonsInstantiated() {
        long started = System.nanoTime();
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean abandoned = new AtomicBoolean();
        FutureTask<RuntimeException> task = new FutureTask<>(() -> pingWithRetry(attempts, abandoned));
        try {
            Thread.ofPlatform().daemon().name("redis-warmup").start(task);
            RuntimeException retriedAfter = task.get(budget.toNanos(), TimeUnit.NANOSECONDS);
            if (retriedAfter == null) {
                log.info("Redis 연결 예열 완료 — 공유 연결을 열고 PING 했다 attempts={} elapsedMs={}", attempts.get(), elapsedMs(started));
            } else {
                log.info("Redis 연결 예열 완료 — 공유 연결을 열고 PING 했다 attempts={} elapsedMs={} retriedAfter={}",
                        attempts.get(), elapsedMs(started), describe(retriedAfter));
            }
        } catch (TimeoutException e) {
            abandoned.set(true);
            task.cancel(true);
            log.warn("Redis 연결 예열 실패 — {} 안에 끝나지 않아 그만둔다, 첫 요청이 다시 연결한다 attempts={} elapsedMs={}",
                    budget, attempts.get(), elapsedMs(started));
        } catch (ExecutionException e) {
            log.warn("Redis 연결 예열 실패 — 첫 요청이 다시 연결한다 attempts={} elapsedMs={} cause={}",
                    attempts.get(), elapsedMs(started), describe(e.getCause() != null ? e.getCause() : e));
        } catch (InterruptedException e) {
            abandoned.set(true);
            task.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("Redis 연결 예열 중 기동 스레드가 인터럽트됐다 — 그만둔다 attempts={} elapsedMs={}", attempts.get(), elapsedMs(started));
        } catch (RuntimeException e) {
            log.warn("Redis 연결 예열을 시작하지 못했다 — 첫 요청이 연결한다 cause={}", describe(e));
        }
    }

    /**
     * 성공하면 그 앞에서 실패한 시도의 예외(없으면 null)를 돌려준다. 모두 실패하면 마지막 예외를 던진다.
     * 기동이 기다리기를 그만두면(abandoned) 새 시도를 하지 않는다 — 인터럽트 표시는 감싸는 쪽이 지울 수 있어 따로 둔다.
     */
    private RuntimeException pingWithRetry(AtomicInteger attempts, AtomicBoolean abandoned) {
        RuntimeException failure = null;
        while (attempts.get() < MAX_ATTEMPTS && !abandoned.get()) {
            attempts.incrementAndGet();
            try (RedisConnection connection = connections.getConnection()) {
                connection.ping();
                return failure;
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        throw failure != null ? failure : new IllegalStateException("기동이 기다리기를 그만둬 시도하지 않았다");
    }

    private static long elapsedMs(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    /** 맨 바깥 예외와 가장 안쪽 원인 — Spring 이 감싼 "Unable to connect to Redis" 만으로는 제한 시간인지 거부인지 모른다. */
    private static String describe(Throwable e) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        return root == e ? e.toString() : e + " ← " + root;
    }
}
