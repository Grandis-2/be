package com.grandis.nova.batch.reconciliation.check;

/**
 * Mock ↔ be 확인할 것 하나(결과-테이블-설계 3장의 코드 하나). 판정 보류가 아닌 키마다 한 번씩 불린다.
 * 어긋나면 {@link FindingSink} 에 남긴다. DB 를 읽거나 쓰지 않는다 — 판정에 필요한 값은 {@link KeyPair} 에 있다.
 */
public interface Check {

    void inspect(KeyPair pair, FindingSink findings);
}
