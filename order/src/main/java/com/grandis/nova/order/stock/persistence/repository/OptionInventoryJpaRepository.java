package com.grandis.nova.order.stock.persistence.repository;

import com.grandis.nova.order.stock.persistence.entity.OptionInventoryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 읽기는 엔티티가 아니라 투영으로 한다. 벌크 UPDATE 는 영속성 컨텍스트를 거치지 않으므로, 엔티티로 읽어 두면
 * 같은 트랜잭션의 다음 조회가 옛 값을 돌려준다(OrderJpaRepository 와 같은 문제).
 * 투영 결과의 option_id 는 바이트로 온다 — 인터페이스 투영은 BINARY(16) 을 UUID 로 바꿔 주지 않는다.
 */
public interface OptionInventoryJpaRepository extends JpaRepository<OptionInventoryJpaEntity, UUID> {

    @Query(value = """
            SELECT option_id AS optionId, stock_total AS stockTotal, stock_reserved AS stockReserved,
                   stock_sold AS stockSold
              FROM option_inventories
             WHERE option_id IN (:ids)
             ORDER BY option_id
               FOR UPDATE
            """, nativeQuery = true)
    List<InventoryRow> findForUpdate(@Param("ids") Collection<UUID> optionIds);

    @Query(value = """
            SELECT option_id AS optionId, stock_total AS stockTotal, stock_reserved AS stockReserved,
                   stock_sold AS stockSold
              FROM option_inventories
             WHERE option_id IN (:ids)
             ORDER BY option_id
            """, nativeQuery = true)
    List<InventoryRow> findRows(@Param("ids") Collection<UUID> optionIds);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE option_inventories SET stock_total = :total, updated_at = :now
             WHERE option_id = :id AND stock_reserved + stock_sold <= :total
            """, nativeQuery = true)
    int changeTotal(@Param("id") UUID optionId, @Param("total") int total, @Param("now") Instant now);

    interface InventoryRow {

        byte[] getOptionId();

        int getStockTotal();

        int getStockReserved();

        int getStockSold();
    }
}
