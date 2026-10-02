package com.grandis.nova.order.stock.persistence.repository;

import com.grandis.nova.order.stock.persistence.entity.OptionInventoryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 읽기는 엔티티가 아니라 투영으로 한다. 벌크 UPDATE 는 영속성 컨텍스트를 거치지 않으므로, 엔티티로 읽어 두면
 * 같은 트랜잭션의 다음 조회가 옛 값을 돌려준다(OrderJpaRepository 와 같은 문제).
 */
public interface OptionInventoryJpaRepository extends JpaRepository<OptionInventoryJpaEntity, Long> {

    @Query(value = """
            SELECT option_id AS optionId, stock_total AS stockTotal, stock_reserved AS stockReserved,
                   stock_sold AS stockSold
              FROM option_inventories
             WHERE option_id IN (:ids)
             ORDER BY option_id
               FOR UPDATE
            """, nativeQuery = true)
    List<InventoryRow> findForUpdate(@Param("ids") Collection<Long> optionIds);

    @Query(value = """
            SELECT option_id AS optionId, stock_total AS stockTotal, stock_reserved AS stockReserved,
                   stock_sold AS stockSold
              FROM option_inventories
             WHERE option_id IN (:ids)
             ORDER BY option_id
            """, nativeQuery = true)
    List<InventoryRow> findRows(@Param("ids") Collection<Long> optionIds);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE option_inventories SET stock_total = :total, updated_at = :now
             WHERE option_id = :id AND stock_reserved + stock_sold <= :total
            """, nativeQuery = true)
    int changeTotal(@Param("id") Long optionId, @Param("total") int total, @Param("now") Instant now);

    interface InventoryRow {

        Long getOptionId();

        int getStockTotal();

        int getStockReserved();

        int getStockSold();
    }
}
