package com.grandis.nova.order.stock.domain.repository;

/**
 * 재고 장부 대조를 인스턴스 하나만 돌게 하는 잠금. 대조 트랜잭션 안에서 잡고 그 트랜잭션이 끝나며(커밋 · 롤백 · 연결 끊김) 함께 풀린다 —
 * 잠금이 풀렸는데 앞 대조가 아직 기록하는 경우가 없고, 풀린 뒤 들어온 인스턴스는 앞 대조의 기록을 반드시 본다.
 */
public interface ReconcileLock {

    /**
     * 기다리지 않고 잠근다. 트랜잭션 안에서만 부른다.
     *
     * @return 잡았는가 — 다른 트랜잭션이 쥐고 있으면 false
     */
    boolean tryLock();
}
