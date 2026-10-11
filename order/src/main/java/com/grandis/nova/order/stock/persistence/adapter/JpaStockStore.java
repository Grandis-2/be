package com.grandis.nova.order.stock.persistence.adapter;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.order.stock.domain.exception.StockAlreadyCreatedException;
import com.grandis.nova.order.stock.domain.model.StockLevel;
import com.grandis.nova.order.stock.domain.repository.StockReader;
import com.grandis.nova.order.stock.domain.repository.StockWriter;
import com.grandis.nova.order.stock.persistence.entity.OptionInventoryJpaEntity;
import com.grandis.nova.order.stock.persistence.repository.OptionInventoryJpaRepository;
import com.grandis.nova.order.stock.persistence.repository.OptionInventoryJpaRepository.InventoryRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 재고 읽기 · 쓰기 포트의 JPA 구현. 새 행은 넣자마자 flush 해 PK 중복을 커밋이 아니라 이 자리에서 잡는다.
 * flush 가 던지는 것은 Spring 으로 바뀌기 전의 Hibernate 예외다(@Repository 변환은 이 클래스를 나갈 때 일어난다).
 * 그래서 원인 사슬에서 제약 이름을 찾고, PK 중복이 아니면 그대로 던져 변환에 맡긴다.
 */
@Repository
class JpaStockStore implements StockReader, StockWriter {

    /** ER_DUP_ENTRY */
    static final int MYSQL_DUPLICATE_KEY = 1062;

    /** MySQL 은 PK 중복을 "option_inventories.PRIMARY" 로 알린다. */
    static final String PRIMARY_KEY = "PRIMARY";

    private final OptionInventoryJpaRepository inventories;
    private final EntityManager entityManager;

    JpaStockStore(OptionInventoryJpaRepository inventories, EntityManager entityManager) {
        this.inventories = inventories;
        this.entityManager = entityManager;
    }

    @Override
    public List<StockLevel> findByOptionIds(Collection<UUID> optionIds) {
        if (optionIds.isEmpty()) {
            return List.of();
        }
        return inventories.findRows(optionIds).stream().map(JpaStockStore::toLevel).toList();
    }

    @Override
    public List<StockLevel> lockByOptionIds(Collection<UUID> optionIds) {
        if (optionIds.isEmpty()) {
            return List.of();
        }
        return inventories.findForUpdate(optionIds).stream().map(JpaStockStore::toLevel).toList();
    }

    @Override
    public int changeTotal(UUID optionId, int total, Instant now) {
        return inventories.changeTotal(optionId, total, now);
    }

    @Override
    public int reserve(UUID optionId, int quantity, Instant now) {
        return inventories.reserve(optionId, quantity, now);
    }

    @Override
    public int release(UUID optionId, int quantity, Instant now) {
        return inventories.release(optionId, quantity, now);
    }

    @Override
    public int sell(UUID optionId, int quantity, Instant now) {
        return inventories.sell(optionId, quantity, now);
    }

    @Override
    public void insert(UUID optionId, int total, Instant now) {
        try {
            entityManager.persist(new OptionInventoryJpaEntity(optionId, total, now));
            entityManager.flush();
        } catch (PersistenceException e) {
            if (isPrimaryKeyDuplicate(e)) {
                throw new StockAlreadyCreatedException(optionId, e);
            }
            throw e;
        }
    }

    private static StockLevel toLevel(InventoryRow row) {
        return new StockLevel(UuidBinary.fromBytes(row.getOptionId()), row.getStockTotal(), row.getStockReserved(),
                row.getStockSold());
    }

    /**
     * PK 중복인가. 벤더 코드(1062)로 판정한다 — 이 표의 유일 키는 PK 하나뿐이다.
     * 제약 이름은 보조다: Hibernate 가 이름을 뽑았는데 PK 가 아니면(나중에 유일 키가 더해진 경우) PK 중복으로 보지 않는다.
     */
    private static boolean isPrimaryKeyDuplicate(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                return violation.getErrorCode() == MYSQL_DUPLICATE_KEY
                        && (name == null || name.equals(PRIMARY_KEY) || name.endsWith("." + PRIMARY_KEY));
            }
        }
        return false;
    }
}
