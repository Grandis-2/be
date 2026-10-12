package com.grandis.nova.batch.reconciliation.check;

import com.grandis.nova.batch.reconciliation.mock.MockLedgerEntry;
import com.grandis.nova.batch.reconciliation.snapshot.BePreorder;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Mock ↔ be 확인할 것 9가지(구현-조치 2장). be 예약 상태마다 Mock 행이 어때야 하는지 본다.
 *
 * <pre>
 * be 예약              Mock 행                                  코드
 * 결제 가능 · 결제 완료  없음                                     REGISTERED_NOT_IN_MOCK
 *                     취소                                     REGISTERED_BUT_MOCK_CANCELED
 *                     활성 · 번호 다름                           NUMBER_MISMATCH
 *                     활성 · 회원 · 상품 · sku 다름                CONTENT_MISMATCH
 * 취소 완료            활성                                     CANCELED_BUT_MOCK_ACTIVE
 *                     없음                                     CANCELED_WITHOUT_MOCK_MARKER
 * 등록 대기            취소 표식                                 PENDING_BUT_MOCK_CANCEL_MARKER
 *                     활성 · 등록 작업 DEAD_LETTER               PENDING_BUT_MOCK_ACTIVE
 * 없음                활성                                     MOCK_ONLY
 * </pre>
 *
 * 그 밖에는 불일치가 아니다. 등록 대기 · 취소 중은 Mock 과 달라도 처리 중이다 — 오래 멈췄으면 {@link PreorderRowCheck} 의 STUCK 이
 * 잡는다. 취소 완료 예약의 Mock 행은 취소(번호 있음 · 표식 모두)면 맞다.
 */
@Component
class MockLedgerCheck implements Check {

    @Override
    public void inspect(KeyPair pair, FindingSink findings) {
        BePreorder be = pair.be();
        MockLedgerEntry mock = pair.mock();
        if (be == null) {
            if (mock.active()) {
                findings.add(CheckCode.MOCK_ONLY, pair.key(), null, mock);
            }
        } else if (be.registered()) {
            registered(pair.key(), be, mock, findings);
        } else if (be.canceled()) {
            if (mock == null) {
                findings.add(CheckCode.CANCELED_WITHOUT_MOCK_MARKER, pair.key(), be, null);
            } else if (mock.active()) {
                findings.add(CheckCode.CANCELED_BUT_MOCK_ACTIVE, pair.key(), be, mock);
            }
        } else if (be.pendingSync() && mock != null) {
            if (mock.cancelMarker()) {
                findings.add(CheckCode.PENDING_BUT_MOCK_CANCEL_MARKER, pair.key(), be, mock);
            } else if (mock.active() && be.registerDeadLettered()) {
                findings.add(CheckCode.PENDING_BUT_MOCK_ACTIVE, pair.key(), be, mock);
            }
        }
    }

    /** 결제 가능 · 결제 완료 — Mock 에 같은 번호 · 같은 내용으로 활성이어야 한다 */
    private static void registered(String key, BePreorder be, MockLedgerEntry mock, FindingSink findings) {
        if (mock == null) {
            findings.add(CheckCode.REGISTERED_NOT_IN_MOCK, key, be, null);
            return;
        }
        if (!mock.active()) {
            findings.add(CheckCode.REGISTERED_BUT_MOCK_CANCELED, key, be, mock);
            return;
        }
        // 번호가 비었으면 NUMBER_MISSING(be 내부)이 잡는다
        if (be.externalReference() != null && !be.externalReference().equals(mock.externalNumber())) {
            findings.add(CheckCode.NUMBER_MISMATCH, key, be, mock);
        }
        if (!Objects.equals(be.customerId(), mock.customerId()) || !Objects.equals(be.productId(), mock.productId())
                || !Objects.equals(be.sku(), mock.sku())) {
            findings.add(CheckCode.CONTENT_MISMATCH, key, be, mock);
        }
    }
}
