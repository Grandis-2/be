package com.grandis.nova.preorder.reconciliation.application;

import com.grandis.nova.preorder.reconciliation.domain.RunRow;

import java.util.Map;

/**
 * 실행 한 줄과 코드별 불일치 건수. 판정(일치 · 불일치 · 판정 불가)은 상태와 건수로 정한다 — 저장하지 않는다.
 *
 * @param findingCounts 코드 → 건수. 불일치가 없거나 끝나지 않았으면 비어 있다
 */
public record RunView(RunRow run, Map<String, Long> findingCounts) {

    public long findingCount() {
        return findingCounts.values().stream().mapToLong(Long::longValue).sum();
    }

    /** 완료면 불일치가 없을 때 일치, 있으면 불일치. 실패는 판정 불가. 대기 · 실행 중이면 아직 없다(null) */
    public Verdict verdict() {
        return switch (run.status()) {
            case COMPLETED -> findingCount() == 0 ? Verdict.MATCHED : Verdict.MISMATCHED;
            case FAILED -> Verdict.UNDETERMINED;
            case REQUESTED, RUNNING -> null;
        };
    }

    public enum Verdict {
        MATCHED,
        MISMATCHED,
        UNDETERMINED
    }
}
