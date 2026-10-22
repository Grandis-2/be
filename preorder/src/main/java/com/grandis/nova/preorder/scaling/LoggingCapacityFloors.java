package com.grandis.nova.preorder.scaling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 꺼진 동안 쓴다 — AWS 를 부르지 않고 계산한 값이 바뀔 때만 로그를 남긴다(켜기 전에 계산을 확인한다). */
class LoggingCapacityFloors implements CapacityFloors {

    private static final Logger log = LoggerFactory.getLogger(LoggingCapacityFloors.class);

    private final Map<String, Integer> last = new ConcurrentHashMap<>();

    @Override
    public boolean ensure(String service, int min) {
        Integer previous = last.put(service, min);
        if (previous != null && previous == min) {
            return false;
        }
        log.info("최소 대수를 바꿀 때다(꺼져 있어 넣지 않는다) service={} {} → {}", service, previous, min);
        return true;
    }
}
