package com.grandis.nova.order.order.persistence.adapter;

import com.grandis.nova.order.order.persistence.repository.OrderEventJpaRepository;
import com.grandis.nova.order.order.persistence.repository.OrderItemJpaRepository;
import com.grandis.nova.order.order.persistence.repository.OrderJpaRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 빈 페이지의 항목 조회는 쿼리를 내지 않는다(빈 IN 절을 DB 에 보내지 않는다). */
class JpaOrderStoreItemsTest {

    @Test
    void noOrderIdsMeansNoQuery() {
        OrderItemJpaRepository items = mock(OrderItemJpaRepository.class);
        JpaOrderStore store = new JpaOrderStore(mock(OrderJpaRepository.class), items,
                mock(OrderEventJpaRepository.class), mock(EntityManager.class));

        assertThat(store.findItemsByOrderIds(List.of())).isEmpty();
        verifyNoInteractions(items);
    }
}
