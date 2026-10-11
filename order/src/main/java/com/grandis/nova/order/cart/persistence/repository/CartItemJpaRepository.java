package com.grandis.nova.order.cart.persistence.repository;

import com.grandis.nova.order.cart.persistence.entity.CartItemJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 읽기는 엔티티가 아니라 투영으로 한다 — 벌크 UPDATE 뒤 같은 트랜잭션의 조회가 옛 값을 돌려주지 않게(OptionInventoryJpaRepository 와 같은 이유).
 * 투영의 id 는 바이트로 온다(인터페이스 투영은 BINARY(16) 을 UUID 로 바꿔 주지 않는다).
 */
public interface CartItemJpaRepository extends JpaRepository<CartItemJpaEntity, UUID> {

    String COLUMNS = "id AS id, customer_id AS customerId, option_id AS optionId, warranty_selected AS warrantySelected, quantity AS quantity";

    @Query(value = "SELECT " + COLUMNS + " FROM cart_items WHERE customer_id = :customerId ORDER BY created_at, id",
            nativeQuery = true)
    List<CartRow> findRows(@Param("customerId") UUID customerId);

    /** 그 회원의 줄을 모두 잠근다. READ COMMITTED 라 행이 없으면 잠그는 것이 없다 — 그 경우의 겹침은 유일 키가 잡는다. */
    @Query(value = "SELECT " + COLUMNS + " FROM cart_items WHERE customer_id = :customerId ORDER BY created_at, id FOR UPDATE",
            nativeQuery = true)
    List<CartRow> findRowsForUpdate(@Param("customerId") UUID customerId);

    @Query(value = "SELECT " + COLUMNS + " FROM cart_items WHERE customer_id = :customerId AND id = :id FOR UPDATE",
            nativeQuery = true)
    List<CartRow> findRowForUpdate(@Param("customerId") UUID customerId, @Param("id") UUID id);

    @Query(value = "SELECT COUNT(*) FROM cart_items WHERE customer_id = :customerId", nativeQuery = true)
    long countRows(@Param("customerId") UUID customerId);

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE cart_items SET quantity = :quantity, updated_at = :now WHERE id = :id", nativeQuery = true)
    int changeQuantity(@Param("id") UUID id, @Param("quantity") int quantity, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM cart_items WHERE customer_id = :customerId AND id = :id", nativeQuery = true)
    int deleteRow(@Param("customerId") UUID customerId, @Param("id") UUID id);

    interface CartRow {
        byte[] getId();
        byte[] getCustomerId();
        byte[] getOptionId();
        boolean getWarrantySelected();
        int getQuantity();
    }
}
