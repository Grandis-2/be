package com.grandis.nova.preorder.query.application;

import com.grandis.nova.common.BusinessException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 관리자 운영 현황. 요청이 왔을 때 세고 잠깐 캐시한다 — 화면이 여럿 폴링해도 집계는 주기에 한 번쯤이다.
 * 캐시가 막 비었을 때 겹친 요청은 각자 센다(읽기 전용이라 결과는 같고, 마지막에 쓴 값이 남는다).
 */
@Service
public class PreorderStatsService {

    private final PreorderStatsCounter counter;
    private final PreorderStatsCache cache;

    PreorderStatsService(PreorderStatsCounter counter, PreorderStatsCache cache) {
        this.counter = counter;
        this.cache = cache;
    }

    /**
     * @param productId null 이면 전체
     * @throws BusinessException PRODUCT_NOT_FOUND — 사전예약 회차가 없는 상품(캐시에 남기지 않는다)
     */
    public PreorderStats find(UUID productId) {
        return cache.find(productId).orElseGet(() -> {
            PreorderStats counted = counter.count(productId);
            cache.put(productId, counted);
            return counted;
        });
    }
}
