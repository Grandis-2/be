package com.grandis.nova.preorder.campaign;

import java.time.Instant;
import java.util.UUID;

/**
 * 증설을 맞출 회차 하나. [opensAt, closesAt) 동안 접수한다.
 *
 * @param expectedVisitors 관리자가 넣은 예상 방문자 수. 넣지 않았으면 null
 */
public record ScalingPlan(UUID productId, Instant opensAt, Instant closesAt, Integer expectedVisitors) {
}
