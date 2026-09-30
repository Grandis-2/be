package com.grandis.nova.preorder.syncjob;

/** 일괄 재처리 시작 결과. 대상은 초당 정해진 건수로 이어서 기록된다. */
record BatchReprocess(int targetCount, int skippedCount, long estimatedSeconds) {
}
