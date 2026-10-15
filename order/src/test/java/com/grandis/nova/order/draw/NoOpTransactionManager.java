package com.grandis.nova.order.draw;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** DB 없이 트랜잭션 경계와 동기화(beforeCommit 등)만 도는 관리자 — 단위 시험이 커밋 직전 확인을 태운다. 쓰기 트랜잭션의 커밋 · 롤백 수를 센다(읽기 전용은 세지 않는다). */
class NoOpTransactionManager extends AbstractPlatformTransactionManager {

    int commits;
    int rollbacks;

    @Override
    protected Object doGetTransaction() {
        return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        if (!status.isReadOnly()) {
            commits++;
        }
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        if (!status.isReadOnly()) {
            rollbacks++;
        }
    }
}
