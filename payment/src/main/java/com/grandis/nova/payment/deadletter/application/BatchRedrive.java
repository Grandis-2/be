package com.grandis.nova.payment.deadletter.application;

import java.util.UUID;

/** 일괄 되돌리기 시작 결과. 대상은 초당 정해진 건수로 이어서 보내고, 진행은 batchId 로 본다. */
public record BatchRedrive(UUID batchId, int targetCount, int skippedCount, long estimatedSeconds) {
}
