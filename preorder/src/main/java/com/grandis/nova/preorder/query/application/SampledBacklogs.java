package com.grandis.nova.preorder.query.application;

import com.grandis.nova.preorder.preorder.PreorderBacklog;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** @param products 접수 중 상품마다 */
record SampledBacklogs(Instant at, PreorderBacklog total, Map<UUID, PreorderBacklog> products) {
}
