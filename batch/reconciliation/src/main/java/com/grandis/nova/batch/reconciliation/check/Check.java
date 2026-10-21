package com.grandis.nova.batch.reconciliation.check;

/**
 * 외부 키 하나로 판정하는 확인할 것 — Mock ↔ be 와, 예약 한 건으로 볼 수 있는 be 내부. 판정 보류가 아닌 키마다 한 번씩 불린다.
 * 어긋나면 {@link CheckCode} 로 {@link FindingSink} 에 남긴다(한 확인할 것이 코드 여럿을 낼 수 있다).
 * DB 를 읽거나 쓰지 않는다 — 판정에 필요한 값은 {@link KeyPair} 에 있다.
 */
public interface Check {

    void inspect(KeyPair pair, RunScope scope, FindingSink findings);
}
