package com.grandis.nova.preorder.query.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.campaign.CampaignPhase;
import com.grandis.nova.preorder.web.ValidationFailures;
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
    private final PreorderStatsProperties properties;

    PreorderStatsService(PreorderStatsCounter counter, PreorderStatsCache cache, PreorderStatsProperties properties) {
        this.counter = counter;
        this.cache = cache;
        this.properties = properties;
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

    /**
     * 그 단계 상품들의 현황 한 페이지.
     *
     * @param size null 이면 기본 크기
     * @throws BusinessException VALIDATION_FAILED — 크기가 범위 밖이거나 커서가 깨졌다
     */
    public ProductStatsPage findProducts(CampaignPhase phase, String cursor, Integer size) {
        int pageSize = size == null ? properties.productPageSize() : size;
        if (pageSize < 1 || pageSize > properties.productPageMaxSize()) {
            throw ValidationFailures.of("size", "1~%d 이어야 합니다.".formatted(properties.productPageMaxSize()));
        }
        String key = cursor == null || cursor.isBlank() ? null : cursor;
        PhaseCursor position = PhaseCursor.of(key);
        return cache.findPage(phase, key, pageSize).orElseGet(() -> {
            ProductStatsPage counted = counter.countProducts(phase, position, pageSize);
            cache.putPage(phase, key, pageSize, counted);
            return counted;
        });
    }
}
