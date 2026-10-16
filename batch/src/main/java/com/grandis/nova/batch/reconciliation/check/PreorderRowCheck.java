package com.grandis.nova.batch.reconciliation.check;

import com.grandis.nova.batch.reconciliation.snapshot.BePreorder;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * be 내부 확인할 것 가운데 예약 한 건(예약 · 등록 · 취소 작업 · 마지막 이력 · 배송 차수)으로 판정하는 것(구현-조치 3장).
 * be 예약을 읽으며 한 번에 본다 — 따로 SQL 로 예약 표를 다시 훑지 않는다. 예약 · 작업은 한 스냅샷이라 서로 어긋나 보이지 않는다.
 * 등록 대기 · 취소 중은 처리 중이라 바로 멈춤이 아니다 — 멈춤 경계(시작 − 45분) 전부터 바뀌지 않았을 때만 멈춤이다.
 *
 * 예약 표 밖의 행(미발행 이벤트 · 결과 없는 시도 · 대기 중 등록 작업의 발행 이벤트 · 순번)은 SQL 검사가 본다.
 * be 내부 불일치는 Mock 값을 남기지 않는다(화면의 외부 열이 빈다).
 */
@Component
class PreorderRowCheck implements Check {

    /** 예약 상태마다 등록 작업이 있을 수 있는 상태. 취소를 시작하면 끝나지 않은 등록 작업을 CANCELED 로 무효화한다 */
    private static final Map<String, Set<String>> REGISTER_JOB_BY_STATUS = Map.of(
            BePreorder.PENDING_SYNC, Set.of("PENDING", "PROCESSING", "RETRY_SCHEDULED", BePreorder.DEAD_LETTER),
            BePreorder.REGISTERED, Set.of(BePreorder.SUCCEEDED),
            BePreorder.RESERVED, Set.of(BePreorder.SUCCEEDED),
            BePreorder.CANCELING, Set.of(BePreorder.SUCCEEDED, "CANCELED"),
            BePreorder.CANCELED, Set.of(BePreorder.SUCCEEDED, "CANCELED"));

    @Override
    public void inspect(KeyPair pair, RunScope scope, FindingSink findings) {
        BePreorder be = pair.be();
        if (be == null) {
            return;
        }
        String key = pair.key();
        boolean registerSucceeded = BePreorder.SUCCEEDED.equals(be.registerJobStatus());
        if (be.registerJobStatus() == null) {
            // 접수가 예약 · 등록 작업 · 발행 이벤트를 한 트랜잭션에 넣는다. 작업이 없으면 worker 가 영영 모른다
            findings.add(CheckCode.OUTBOX_MISSING, key, be, null);
        } else if (!REGISTER_JOB_BY_STATUS.getOrDefault(be.status(), Set.of()).contains(be.registerJobStatus())) {
            findings.add(CheckCode.JOB_STATE_MISMATCH, key, be, null);
        }
        if (registerSucceeded && be.externalReference() == null) {
            findings.add(CheckCode.NUMBER_MISSING, key, be, null);
        }
        if (be.externalReference() != null && !registerSucceeded) {
            findings.add(CheckCode.NUMBER_WITHOUT_SUCCESS, key, be, null);
        }
        // 취소 완료는 취소 작업의 성공으로만 간다
        if (be.canceled() && !BePreorder.SUCCEEDED.equals(be.cancelJobStatus())) {
            findings.add(CheckCode.CANCEL_JOB_INCOMPLETE, key, be, null);
        }
        // 등록 작업 DEAD_LETTER 는 멈춤이 아니라 관리자 대기다(실행 행의 건수). 취소 작업은 포기하지 않아 기준 시간을 넘기면 멈춤이다
        if ((be.registering() || be.canceling()) && !be.changedAt().isAfter(scope.stuckBefore())) {
            findings.add(CheckCode.STUCK, key, be, null);
        }
        if (!be.status().equals(be.lastEventStatus())) {
            findings.add(CheckCode.HISTORY_MISMATCH, key, be, null);
        }
        if (be.queuePosition() < be.batchFrom() || (be.batchTo() != null && be.queuePosition() > be.batchTo())) {
            findings.add(CheckCode.SHIPMENT_BATCH_MISMATCH, key, be, null);
        }
    }
}
