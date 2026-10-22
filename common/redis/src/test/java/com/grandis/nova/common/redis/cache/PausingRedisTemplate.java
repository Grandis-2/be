package com.grandis.nova.common.redis.cache;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** 표시한 스레드가 고른 키를 GET 한 직후 멈추는 템플릿 — 읽기와 그 뒤 판단 사이에 다른 스레드를 끼워 넣는 시험용. */
final class PausingRedisTemplate extends StringRedisTemplate {

    final ThreadLocal<Boolean> pause = ThreadLocal.withInitial(() -> false);
    final CountDownLatch read = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);
    /** 멈춘 GET 의 키. */
    volatile String pausedKey;
    /** 표시한 스레드에서 고른 키의 몇 번째 GET 에서 멈출지(1 부터). */
    volatile int pauseAt = 1;
    /** null 이 아니면 표시한 스레드에서 고른 키의 failAt 번째 GET 이 이것을 던진다. */
    volatile Throwable failWith;
    volatile int failAt = 1;
    private final ThreadLocal<Integer> seen = ThreadLocal.withInitial(() -> 0);
    final ThreadLocal<Boolean> fail = ThreadLocal.withInitial(() -> false);
    private final Predicate<String> pauseOn;

    PausingRedisTemplate(RedisConnectionFactory connections, Predicate<String> pauseOn) {
        super(connections);
        this.pauseOn = pauseOn;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ValueOperations<String, String> opsForValue() {
        ValueOperations<String, String> real = super.opsForValue();
        return (ValueOperations<String, String>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ValueOperations.class},
                (proxy, method, args) -> {
                    boolean watched = method.getName().equals("get") && args != null && args.length == 1 && args[0] instanceof String key0
                            && pauseOn.test(key0) && (pause.get() || fail.get());
                    int nth = watched ? seen.get() + 1 : 0;
                    if (watched) {
                        seen.set(nth);
                    }
                    if (watched && fail.get() && failWith != null && nth == failAt) {
                        fail.set(false);
                        if (pause.get() && nth == pauseAt) {   // 던지기 전에 멈춘다 — 그사이 다른 요청을 끼워 넣게
                            pause.set(false);
                            pausedKey = (String) args[0];
                            read.countDown();
                            if (!resume.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("시험이 멈춘 조회를 풀지 않았다");
                            }
                        }
                        throw failWith;
                    }
                    Object result;
                    try {
                        result = method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (watched && pause.get() && nth == pauseAt) {
                        pause.set(false);
                        pausedKey = (String) args[0];
                        read.countDown();
                        if (!resume.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("시험이 멈춘 조회를 풀지 않았다");
                        }
                    }
                    return result;
                });
    }
}
