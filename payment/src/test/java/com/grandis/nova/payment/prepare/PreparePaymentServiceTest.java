package com.grandis.nova.payment.prepare;

import com.grandis.nova.payment.support.PaymentFixtures;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import com.grandis.nova.payment.vo.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@PaymentIntegrationTest
class PreparePaymentServiceTest {

    @Autowired
    PreparePaymentService service;

    @Autowired
    PlatformTransactionManager transactionManager;

    // 바깥 트랜잭션에 참여하면 거래 하나 = 트랜잭션 하나(원장 계약)가 깨진다.
    @Test
    void refusesToJoinOuterTransaction() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outer.executeWithoutResult(status ->
                service.open(PaymentFixtures.newOrderTarget(), Money.won(1000))))
                .isInstanceOf(IllegalStateException.class);
    }
}
