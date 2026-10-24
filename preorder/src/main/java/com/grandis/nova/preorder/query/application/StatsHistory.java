package com.grandis.nova.preorder.query.application;

import java.util.List;

/**
 * @param stepSeconds 기록 주기
 * @param samples     시각 순. 기록이 멈췄던 동안은 비어 있다
 */
public record StatsHistory(long stepSeconds, List<StatsSample> samples) {
}
