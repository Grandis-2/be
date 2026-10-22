package com.grandis.nova.batch.reconciliation.check;

import com.grandis.nova.batch.reconciliation.mock.MockLedgerEntry;
import com.grandis.nova.batch.reconciliation.run.RunCounts;
import com.grandis.nova.batch.reconciliation.snapshot.BePreorder;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * be 예약과 Mock 원장을 외부 키 순서로 맞대어 키마다 확인할 것({@link Check})에 넘기고, 끝나면 be 내부 확인할 것을 돌린다.
 *
 * <ul>
 *   <li>둘 다 키 순서로 흘러오므로 작은 쪽을 한 칸씩 넘기며 맞댄다 — 한쪽에만 있는 키도 한 번씩 나온다</li>
 *   <li>be 첫 건을 Mock 첫 페이지보다 먼저 읽는다. 검사 트랜잭션의 스냅샷이 그때 정해져 be 가 Mock 보다 앞선 시점이 된다</li>
 *   <li>Mock 행이 판정 보류 경계(시작 − 2분) 뒤에 바뀌었으면 — 검사 도중 바뀐 행이면 — 판정하지 않고 판정 보류로 센다.
 *       be 쪽 변경 시각은 보지 않는다(be 가 Mock 보다 앞설 수 없다). 처리 중인 예약은 확인할 것이 상태로 가린다</li>
 *   <li>범위가 상품 하나면 다른 상품의 Mock 행(be 에 그 키가 없는 행)은 버린다. 상품을 모르는 취소 표식도 같다.
 *       Mock 행은 이 상품인데 키가 다른 상품의 be 예약이면 이 실행에서 빼고 전체 검사가 "내용 다름" 으로 잡게 둔다</li>
 *   <li>불일치가 상한을 넘으면 {@link TooManyFindings} — 그 실행은 판정 불가다</li>
 *   <li>처리 중 · 관리자 대기 수는 판정 보류와 상관없이 be 예약 상태로 센다(미완료 현황)</li>
 * </ul>
 */
@Component
public class Reconciler {

    private final List<Check> checks;
    private final List<InternalCheck> internalChecks;
    private final JsonMapper json;

    public Reconciler(List<Check> checks, List<InternalCheck> internalChecks, JsonMapper json) {
        this.checks = List.copyOf(checks);
        this.internalChecks = List.copyOf(internalChecks);
        this.json = json;
    }

    /**
     * 검사 트랜잭션 안에서 부른다.
     *
     * @param be   be 예약(외부 키 순서). 범위가 상품 하나면 그 상품의 예약만
     * @param mock Mock 원장(외부 키 순서). 전체
     */
    public ReconcileResult reconcile(Iterator<BePreorder> be, Iterator<MockLedgerEntry> mock, RunScope scope) {
        FindingSink findings = new FindingSink(json, scope.maxFindings());
        Tally tally = new Tally();
        BePreorder b = next(be);
        MockLedgerEntry m = next(mock);
        while (b != null || m != null) {
            int order = b == null ? 1 : m == null ? -1 : b.token().compareTo(m.externalKey());
            KeyPair pair;
            if (order < 0) {
                pair = new KeyPair(b.token(), b, null);
                b = next(be);
            } else if (order > 0) {
                pair = new KeyPair(m.externalKey(), null, m);
                m = next(mock);
            } else {
                pair = new KeyPair(b.token(), b, m);
                b = next(be);
                m = next(mock);
            }
            if (pair.be() != null) {
                tally.count(pair.be());
            }
            if (outOfScope(pair, scope)) {
                continue;
            }
            if (mockChangedAfter(pair, scope.cutoff())) {
                tally.held++;
                continue;
            }
            for (Check check : checks) {
                check.inspect(pair, scope, findings);
            }
        }
        for (InternalCheck check : internalChecks) {
            check.inspect(scope, findings);
        }
        return new ReconcileResult(tally.counts(), findings.findings());
    }

    private static boolean outOfScope(KeyPair pair, RunScope scope) {
        if (scope.productId() == null || pair.be() != null) {
            return false;
        }
        if (!scope.productId().toString().equals(pair.mock().productId())) {
            return true;
        }
        // 이 상품의 예약만 읽었으니, be 어딘가에 이 키가 있다면 다른 상품의 예약이다
        return scope.beHasKey().test(pair.key());
    }

    /**
     * 검사 도중 Mock 이 바뀌었나. be 를 먼저 읽어(한 스냅샷) be 는 Mock 보다 앞설 수 없다 — 정상 흐름은 Mock 이 먼저 바뀌고 be 가 나중에
     * 바뀐다. 그래서 어긋나 보이는 건 스냅샷 뒤에 Mock 이 바뀐 경우뿐이고, be 가 Mock 결과를 아직 반영하지 못한 건 처리 중(등록 대기 ·
     * 취소 중)으로 센다. Mock 시각은 Mock 서버 시계라 경계에 시계 차이 여유를 둔다
     */
    private static boolean mockChangedAfter(KeyPair pair, Instant cutoff) {
        return pair.mock() != null && pair.mock().changedAt() != null && pair.mock().changedAt().isAfter(cutoff);
    }

    private static <T> T next(Iterator<T> rows) {
        return rows.hasNext() ? rows.next() : null;
    }

    private static final class Tally {

        int held;
        int pendingSync;
        int canceling;
        int deadLetter;

        void count(BePreorder preorder) {
            if (preorder.registering()) {
                pendingSync++;
            } else if (preorder.registerDeadLettered() || preorder.cancelDeadLettered()) {
                deadLetter++;
            } else if (preorder.canceling()) {
                canceling++;
            }
        }

        RunCounts counts() {
            return new RunCounts(held, pendingSync, canceling, deadLetter);
        }
    }
}
