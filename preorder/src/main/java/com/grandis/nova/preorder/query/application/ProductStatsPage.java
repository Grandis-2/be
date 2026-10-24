package com.grandis.nova.preorder.query.application;

import java.time.Instant;
import java.util.List;

/**
 * 상품별 운영 현황 한 페이지.
 *
 * @param nextCursor 다음 페이지 커서. 마지막 페이지면 null
 * @param countedAt  센 시각. 캐시된 값이면 지금보다 이전이다
 */
public record ProductStatsPage(List<ProductStats> items, String nextCursor, Instant countedAt) {
}
