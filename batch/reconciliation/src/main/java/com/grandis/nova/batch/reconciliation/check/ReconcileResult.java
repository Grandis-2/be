package com.grandis.nova.batch.reconciliation.check;

import com.grandis.nova.batch.reconciliation.run.NewFinding;
import com.grandis.nova.batch.reconciliation.run.RunCounts;
import java.util.List;

/** 검사 한 번의 결과 — 건별 행 없이 세는 수와 찾은 불일치 */
public record ReconcileResult(RunCounts counts, List<NewFinding> findings) {
}
