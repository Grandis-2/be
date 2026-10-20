package com.grandis.nova.preorder.reconciliation.application;

import com.grandis.nova.preorder.reconciliation.domain.FindingKey;

import java.util.List;
import java.util.UUID;

/**
 * 두 실행의 불일치를 (코드, 대상)으로 맞댄 결과. 해소는 그 건이 이번 검사에서 나오지 않았다는 사실일 뿐이다 —
 * 조치로 고쳐졌는지는 그 키의 처리 이력으로 확인한다.
 *
 * @param introduced 신규 — run 에만 있다
 * @param persisting 잔여 — 둘 다 있다
 * @param resolved   해소 — against 에만 있다
 */
public record RunDiff(UUID runId, UUID againstRunId, List<FindingKey> introduced, List<FindingKey> persisting,
                      List<FindingKey> resolved) {
}
