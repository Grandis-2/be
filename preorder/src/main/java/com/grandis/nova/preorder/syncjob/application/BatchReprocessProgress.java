package com.grandis.nova.preorder.syncjob.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 일괄 재처리 진행. 결과 수는 센 값이 아니라 조회할 때 대상 작업의 지금 상태다 — 결과는 worker 가 늦게 낸다.
 *
 * @param sent           재처리 요청을 남긴 수
 * @param succeeded      보낸 뒤 성공한 수
 * @param failed         보낸 뒤 다시 포기(DEAD_LETTER)된 수
 * @param canceled       보낸 뒤 예약 취소로 무효가 된 수
 * @param pending        아직 보내지 않았거나 결과를 기다리는 수
 * @param lastProgressAt 마지막으로 대상을 다룬 시각. 끝나지 않았는데 오래 그대로면 보내던 인스턴스가 멈춘 것이다
 * @param finishedAt     모두 다룬 시각. 아직이면 null
 */
public record BatchReprocessProgress(UUID batchId, int targetCount, int skippedCount, int sent, int succeeded,
                                     int failed, int canceled, int pending, Instant startedAt,
                                     Instant lastProgressAt, Instant finishedAt) {
}
