package com.grandis.nova.waitingroom.entry;

import com.grandis.nova.waitingroom.domain.product.SalesWindow;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 접수가 "마감" 이라고 답한 모델. 이 노드는 그 즉시 진입을 닫는다 — 일정 이벤트가 다른 노드에 닿기 전의 격차를 줄인다.
 * 그때의 접수 기간과 함께 기억해, 운영자가 일정을 바꿔 판정 재료의 기간이 달라지면 저절로 풀린다.
 */
@Component
public class ProductClosures {

    /** 모델 수만큼만 는다. 넘으면 비운다 — 잊어도 판정 재료의 마감 시각이 곧 따라온다. */
    private static final int MAX_PRODUCTS = 10_000;

    private final Map<String, SalesWindow> closed = new ConcurrentHashMap<>();

    public void observeClosed(String productKey, SalesWindow window) {
        if (closed.size() >= MAX_PRODUCTS) {
            closed.clear();
        }
        closed.put(productKey, window);
    }

    public boolean closed(String productKey, SalesWindow current) {
        return current.equals(closed.get(productKey));
    }
}
