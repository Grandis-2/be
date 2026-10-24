package com.grandis.nova.preorder.query.application;

import com.grandis.nova.preorder.preorder.PreorderStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * @param total    전체 상태별 수
 * @param products 접수 중 상품마다 상태별 수
 */
record SampledCounts(Instant at, Map<PreorderStatus, Long> total, Map<UUID, Map<PreorderStatus, Long>> products) {
}
