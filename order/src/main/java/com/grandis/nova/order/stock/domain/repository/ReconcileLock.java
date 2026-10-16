package com.grandis.nova.order.stock.domain.repository;

/**
 * 재고 장부 대조를 인스턴스 하나만 돌게 하는 잠금. 트랜잭션 안에서 잡고 같은 트랜잭션 안에서 놓는다 — DB 연결에 묶인 잠금이라
 * 같은 연결이어야 놓을 수 있고, 연결이 끊기면 저절로 풀린다.
 */
public interface ReconcileLock {

    /** 기다리지 않는다. @return 잡았으면 true, 다른 연결이 쥐고 있으면 false */
    boolean tryLock();

    void unlock();
}
