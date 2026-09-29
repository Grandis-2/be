package com.grandis.nova.order.order.persistence.repository;

import com.grandis.nova.order.order.persistence.entity.OrderEventJpaEntity;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * 이력 읽기 전용. 이력은 추가 전용이고 쓰기는 원장이 어댑터의 appendEvent(EntityManager.persist)로만 한다 —
 * JpaRepository 를 상속하면 save · delete 가 열려 원장 밖에서 이력을 고치거나 지울 길이 생긴다.
 */
public interface OrderEventJpaRepository extends Repository<OrderEventJpaEntity, OrderEventJpaEntity.Key> {

    /** PK (order_id, event_sequence) 범위로 읽는다. */
    List<OrderEventJpaEntity> findByOrderIdAndEventSequenceLessThanEqualOrderByEventSequence(Long orderId,
                                                                                           Long upToSequence);
}
