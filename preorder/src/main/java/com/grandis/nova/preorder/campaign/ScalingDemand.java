package com.grandis.nova.preorder.campaign;

/**
 * 증설을 맞출 회차들의 요약 — 최소 대수는 회차 수와 가장 큰 예상 방문자 수만 본다.
 *
 * @param campaigns           걸린 회차 수
 * @param maxExpectedVisitors 그중 가장 큰 예상 방문자 수. 넣은 회차가 없으면 null
 */
public record ScalingDemand(long campaigns, Integer maxExpectedVisitors) {
}
