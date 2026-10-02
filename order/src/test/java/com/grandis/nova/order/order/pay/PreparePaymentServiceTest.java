package com.grandis.nova.order.order.pay;

import com.grandis.nova.order.client.payment.PaymentPreparer;
import com.grandis.nova.order.client.preorder.PreorderReader;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PreparePaymentServiceTest {

    final OrderReader orderReader = mock(OrderReader.class);
    final PreorderReader preorderReader = mock(PreorderReader.class);
    final PaymentPreparer paymentPreparer = mock(PaymentPreparer.class);
    final PreparePaymentService service = new PreparePaymentService(orderReader,
            new PayabilityGate(preorderReader, mock(OrderLedger.class), mock(PlatformTransactionManager.class)),
            paymentPreparer, mock(PlatformTransactionManager.class));

    // 바깥 트랜잭션 안에서 부르면 preorder · payment 응답을 기다리는 동안 그 트랜잭션의 잠금을 쥔다.
    @Test
    void refusesToRunInsideTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.prepare(1L, "prepare-guard-test", "6f1c2d3e-4b5a-4c7d-8e9f-0a1b2c3d4e5f"))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(orderReader, preorderReader, paymentPreparer);
    }
}
