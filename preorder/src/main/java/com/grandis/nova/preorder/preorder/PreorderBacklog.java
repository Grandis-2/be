package com.grandis.nova.preorder.preorder;

import java.time.Instant;
import java.util.Map;

/**
 * 운영 현황용 예약 수.
 *
 * @param byStatus            상태별 수. 한 건도 없는 상태는 0 이다
 * @param paymentOverdue      결제 기한이 지났는데 아직 만료 처리 전인 수
 * @param oldestPendingSyncAt  외부 등록을 기다리는 예약 중 가장 오래된 접수 시각. 재처리를 기다리는(DEAD_LETTER) 예약도 든다. 없으면 null
 * @param oldestInFlightSyncAt 그중 등록 작업이 아직 도는(대기 · 처리 중 · 재시도 대기) 예약만의 가장 오래된 접수 시각. 없으면 null
 */
public record PreorderBacklog(Map<PreorderStatus, Long> byStatus, long paymentOverdue, Instant oldestPendingSyncAt,
                              Instant oldestInFlightSyncAt) {
}
