package com.grandis.nova.preorder.reconciliation.api;

import com.grandis.nova.preorder.reconciliation.application.RunView;
import com.grandis.nova.preorder.reconciliation.application.RunView.Verdict;
import com.grandis.nova.preorder.reconciliation.domain.RunRow;
import com.grandis.nova.preorder.reconciliation.domain.RunStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 검사 실행 한 줄. 화면의 최근 결과 카드 · 실행 이력 표.
 *
 * @param verdict          MATCHED(일치) · MISMATCHED(불일치) · UNDETERMINED(판정 불가 — 실패). 대기 · 실행 중이면 null
 * @param requestedBy      실행을 요청한 관리자. null 이면 정기 실행
 * @param productId        검사 범위. null 이면 전체(모델 이름은 catalog 에서 읽는다)
 * @param cutoffAt         판정 기준 시각. 이 뒤에 바뀐 건은 판정하지 않고 heldCount 로만 셌다
 * @param errorMessage     실패 사유(판정 불가). 예: "Mock 목록 조회 실패(3페이지, 시간 초과)"
 * @param findingCounts    코드별 불일치 건수
 * @param heldCount        판정 보류. 완료 전에는 null(아래 셋도 같다)
 * @param pendingSyncCount 처리 중 — 등록 대기
 * @param cancelingCount   처리 중 — 취소 중
 * @param deadLetterCount  관리자 대기 — 등록 작업 DEAD_LETTER
 */
record RunResponse(UUID runId, RunStatus status, Verdict verdict, String requestedBy, UUID productId,
                   Instant createdAt, Instant startedAt, Instant cutoffAt, Instant finishedAt, String errorMessage,
                   long findingCount, Map<String, Long> findingCounts, Integer heldCount, Integer pendingSyncCount,
                   Integer cancelingCount, Integer deadLetterCount) {

    static RunResponse from(RunView view) {
        RunRow run = view.run();
        return new RunResponse(run.id(), run.status(), view.verdict(), run.requestedBy(), run.productId(),
                run.createdAt(), run.startedAt(), run.cutoffAt(), run.finishedAt(), run.errorMessage(),
                view.findingCount(), view.findingCounts(), run.heldCount(), run.pendingSyncCount(),
                run.cancelingCount(), run.deadLetterCount());
    }
}
