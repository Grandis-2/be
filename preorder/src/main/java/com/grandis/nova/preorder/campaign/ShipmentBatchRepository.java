package com.grandis.nova.preorder.campaign;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShipmentBatchRepository extends JpaRepository<ShipmentBatch, Long> {

    List<ShipmentBatch> findByProductIdOrderByBatchNumber(Long productId);

    /** 오픈 전 전체 교체에서 쓴다. 오픈 뒤에는 부르지 않는다(예약이 차수를 가리킨다). */
    void deleteByProductId(Long productId);

    /**
     * 예약에 배정된 차수. 접수 때 정해지고 바뀌지 않으므로 없을 수 없다 —
     * 없으면 오픈 전 검사를 지나친 데이터다.
     */
    default ShipmentBatch getAssigned(Long shipmentBatchId) {
        return findById(shipmentBatchId)
                .orElseThrow(() -> new IllegalStateException("예약의 배송 차수가 없다: shipmentBatchId=" + shipmentBatchId));
    }

    /**
     * 순번이 속한 차수. 구간이 겹치지 않는다는 것은 오픈 전 검사가 보장한다(ERD: UNIQUE 는 "최대 1개" 만 보장).
     * 비어 있으면 차수 설정이 잘못된 것이다 — 상한 없는 마지막 차수가 있으면 모든 순번이 어딘가에 속한다.
     */
    @Query("""
            select b from ShipmentBatch b
            where b.productId = :productId
              and b.positionFrom <= :position
              and (b.positionTo is null or b.positionTo >= :position)
            """)
    Optional<ShipmentBatch> findCovering(@Param("productId") Long productId, @Param("position") long position);
}
