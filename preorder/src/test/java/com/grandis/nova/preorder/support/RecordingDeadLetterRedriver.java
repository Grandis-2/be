package com.grandis.nova.preorder.support;

import com.grandis.nova.preorder.deadletter.DeadLetterRedriver;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 큐로 보내지 않고 되돌리기를 행 id 별로 기록하는 대역. 컨텍스트를 공유하는 테스트끼리 섞이지 않게 id 로만 묻는다.
 * failFor 로 지정한 id 는 보내기에 실패한다.
 */
public class RecordingDeadLetterRedriver implements DeadLetterRedriver {

    private final Map<UUID, List<Sent>> sent = new ConcurrentHashMap<>();
    private final Set<UUID> failing = ConcurrentHashMap.newKeySet();

    @Override
    public void redrive(String sourceQueue, String body, UUID deadLetterId) {
        if (failing.contains(deadLetterId)) {
            throw new IllegalStateException("큐로 보내지 못함(테스트)");
        }
        sent.computeIfAbsent(deadLetterId, id -> new CopyOnWriteArrayList<>()).add(new Sent(sourceQueue, body));
    }

    public void failFor(UUID deadLetterId) {
        failing.add(deadLetterId);
    }

    public void recover(UUID deadLetterId) {
        failing.remove(deadLetterId);
    }

    public List<Sent> sentFor(UUID deadLetterId) {
        return List.copyOf(sent.getOrDefault(deadLetterId, List.of()));
    }

    public record Sent(String sourceQueue, String body) {
    }
}
