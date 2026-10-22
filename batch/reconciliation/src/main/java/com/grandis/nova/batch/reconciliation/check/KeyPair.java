package com.grandis.nova.batch.reconciliation.check;

import com.grandis.nova.batch.reconciliation.mock.MockLedgerEntry;
import com.grandis.nova.batch.reconciliation.snapshot.BePreorder;

/**
 * 외부 키 하나에 대한 be 예약과 Mock 원장 행. 한쪽에만 있으면 다른 쪽은 null 이다.
 *
 * @param be   be 예약. "Mock 에만 있음" 이면 null
 * @param mock Mock 원장 행. Mock 에 그 키가 없으면 null
 */
public record KeyPair(String key, BePreorder be, MockLedgerEntry mock) {

    public KeyPair {
        if (be == null && mock == null) {
            throw new IllegalArgumentException("be · Mock 둘 다 없는 키는 맞대지 않는다: " + key);
        }
    }
}
