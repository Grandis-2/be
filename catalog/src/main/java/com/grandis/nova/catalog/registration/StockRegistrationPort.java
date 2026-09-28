package com.grandis.nova.catalog.registration;

import java.util.Map;

/** 등록 ② 의 order 쪽 호출(PUT /admin/products/{id}/stock). 구현은 등록 조율 티켓의 몫이다. */
public interface StockRegistrationPort {

    void putStock(Long productId, Map<Long, Integer> stockByOptionId);
}
