package com.grandis.nova.order.cart.persistence.adapter;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.order.cart.domain.exception.CartSlotTakenException;
import com.grandis.nova.order.cart.domain.model.CartLine;
import com.grandis.nova.order.cart.domain.repository.CartStore;
import com.grandis.nova.order.cart.persistence.entity.CartItemJpaEntity;
import com.grandis.nova.order.cart.persistence.repository.CartItemJpaRepository;
import com.grandis.nova.order.cart.persistence.repository.CartItemJpaRepository.CartRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 장바구니 포트의 JPA 구현. 새 줄은 넣자마자 flush 해 유일 키 충돌을 커밋이 아니라 이 자리에서 잡는다(JpaStockStore 와 같은 방식).
 */
@Repository
class JpaCartStore implements CartStore {

    /** ER_DUP_ENTRY */
    static final int MYSQL_DUPLICATE_KEY = 1062;
    /** (회원, 옵션, 보증) 유일 키. MySQL 은 "cart_items.uq_cart_customer_option_warranty" 로 알린다. */
    static final String SLOT_KEY = "uq_cart_customer_option_warranty";

    private final CartItemJpaRepository items;
    private final EntityManager entityManager;

    JpaCartStore(CartItemJpaRepository items, EntityManager entityManager) {
        this.items = items;
        this.entityManager = entityManager;
    }

    @Override
    public List<CartLine> findByCustomer(UUID customerId) {
        return items.findRows(customerId).stream().map(JpaCartStore::toLine).toList();
    }

    /**
     * 잠금 읽기를 두 번 한다. 첫 번째는 앞선 쓰기의 커밋을 기다리는 줄서기다 — 기다리던 잠금 읽기는 그 자리부터 이어 읽어, 앞선 쓰기가 넣은 줄이
     * 유일 키(customer_id, option_id, warranty_selected)에서 기존 줄보다 앞(옵션 id 가 작음)이면 못 본다. 두 번째는 새 문장이라 처음부터
     * 다시 훑어 그 줄까지 본다 — 첫 읽기가 끝났으면 그 회원 줄을 쥐었던 앞선 쓰기는 모두 커밋했다(MySQL 8.4.11 · READ COMMITTED,
     * 유일 키로 훑는 계획에서 실측: 한 번이면 49줄에서 겹친 담기 둘이 51줄, 두 번이면 50줄 — CartConcurrencyTest).
     */
    @Override
    public List<CartLine> lockByCustomer(UUID customerId) {
        items.findRowsForUpdate(customerId);
        return items.findRowsForUpdate(customerId).stream().map(JpaCartStore::toLine).toList();
    }

    @Override
    public Optional<CartLine> lockLine(UUID customerId, UUID lineId) {
        return items.findRowForUpdate(customerId, lineId).stream().map(JpaCartStore::toLine).findFirst();
    }

    @Override
    public long countByCustomer(UUID customerId) {
        return items.countRows(customerId);
    }

    @Override
    public CartLine insert(UUID customerId, UUID optionId, boolean warranty, int quantity) {
        CartItemJpaEntity entity = new CartItemJpaEntity(customerId, optionId, warranty, quantity);
        try {
            entityManager.persist(entity);
            entityManager.flush();
        } catch (PersistenceException e) {
            if (isSlotTaken(e)) {
                throw new CartSlotTakenException(e);
            }
            throw e;
        }
        return new CartLine(entity.getId(), customerId, optionId, warranty, quantity);
    }

    @Override
    public int changeQuantity(UUID lineId, int quantity, Instant now) {
        return items.changeQuantity(lineId, quantity, now);
    }

    @Override
    public int delete(UUID customerId, UUID lineId) {
        return items.deleteRow(customerId, lineId);
    }

    private static CartLine toLine(CartRow row) {
        return new CartLine(UuidBinary.fromBytes(row.getId()), UuidBinary.fromBytes(row.getCustomerId()),
                UuidBinary.fromBytes(row.getOptionId()), row.getWarrantySelected(), row.getQuantity());
    }

    /** 같은 (회원, 옵션, 보증) 줄이 이미 있는가 — 벤더 코드(1062)와 제약 이름으로 판정한다. 다른 제약 위반이면 그대로 던진다. */
    private static boolean isSlotTaken(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                return violation.getErrorCode() == MYSQL_DUPLICATE_KEY
                        && name != null && (name.equals(SLOT_KEY) || name.endsWith("." + SLOT_KEY));
            }
        }
        return false;
    }
}
