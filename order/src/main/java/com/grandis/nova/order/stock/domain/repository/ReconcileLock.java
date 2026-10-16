package com.grandis.nova.order.stock.domain.repository;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 재고 장부 대조를 인스턴스 하나만 돌게 하는 잠금. 일의 트랜잭션 바깥에서 잡고, 일이 끝난 뒤(그 안의 트랜잭션이 커밋 · 롤백된 뒤) 놓는다 —
 * 놓은 뒤 들어온 인스턴스는 앞 인스턴스의 기록을 반드시 본다. 쥔 쪽이 죽으면(연결이 끊기면) 저절로 풀린다.
 */
public interface ReconcileLock {

    /**
     * 잠근 채 work 를 실행한다. 기다리지 않는다. 트랜잭션 안에서 부르면 안 된다 — work 의 트랜잭션이 바깥에 합류해 잠금을 놓은 뒤에 커밋된다.
     *
     * @return work 의 결과. 다른 인스턴스가 쥐고 있어 실행하지 않았으면 비어 있다
     */
    <T> Optional<T> runExclusively(Supplier<T> work);
}
