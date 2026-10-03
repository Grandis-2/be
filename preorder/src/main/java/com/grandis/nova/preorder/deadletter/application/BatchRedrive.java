package com.grandis.nova.preorder.deadletter.application;

/** 일괄 되돌리기 시작 결과. 대상은 초당 정해진 건수로 이어서 보낸다. */
public record BatchRedrive(int targetCount, int skippedCount, long estimatedSeconds) {
}
