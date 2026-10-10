package com.grandis.nova.order.draw.persistence.adapter;

import com.grandis.nova.order.draw.domain.exception.DrawEntryTakenException;
import com.grandis.nova.order.draw.domain.model.DrawEntry;
import com.grandis.nova.order.draw.domain.model.DrawEntryStatus;
import com.grandis.nova.order.draw.domain.model.EntryTransition;
import com.grandis.nova.order.draw.domain.model.NewDrawEntry;
import com.grandis.nova.order.draw.domain.repository.DrawEntryStore;
import com.grandis.nova.order.draw.persistence.entity.DrawEntryJpaEntity;
import com.grandis.nova.order.draw.persistence.repository.DrawEntryJpaRepository;
import com.grandis.nova.order.order.vo.ShipTo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaDrawEntryStore implements DrawEntryStore {

    /** ER_DUP_ENTRY */
    static final int MYSQL_DUPLICATE_KEY = 1062;
    /** (회차, 회원) 유일 키. MySQL 은 "draw_entries.uq_draw_entry_customer" 로 알린다. */
    static final String ENTRY_KEY = "uq_draw_entry_customer";

    private final DrawEntryJpaRepository entries;
    private final EntityManager entityManager;

    JpaDrawEntryStore(DrawEntryJpaRepository entries, EntityManager entityManager) {
        this.entries = entries;
        this.entityManager = entityManager;
    }

    @Override
    public DrawEntry insert(NewDrawEntry e) {
        ShipTo to = e.shipTo();
        DrawEntryJpaEntity entity = new DrawEntryJpaEntity(e.campaignId(), e.customerId(), to.name(), to.phone(), to.postalCode(),
                to.line1(), to.line2());
        try {
            entityManager.persist(entity);
            entityManager.flush();
        } catch (PersistenceException ex) {
            if (isEntryTaken(ex)) {
                throw new DrawEntryTakenException(ex);
            }
            throw ex;
        }
        return toDomain(entity);
    }

    @Override
    public Optional<DrawEntry> findById(UUID id) {
        return entries.findById(id).map(JpaDrawEntryStore::toDomain);
    }

    @Override
    public Optional<DrawEntry> findByCampaignAndCustomer(UUID campaignId, UUID customerId) {
        return entries.findByCampaignIdAndCustomerId(campaignId, customerId).map(JpaDrawEntryStore::toDomain);
    }

    @Override
    public EntryTransition requestPayment(UUID entryId, String providerOrderId, Instant now) {
        return transition(entryId, entries.requestPayment(entryId, providerOrderId, now));
    }

    @Override
    public EntryTransition approve(UUID entryId, Instant now) {
        return transition(entryId, entries.approve(entryId, now));
    }

    @Override
    public EntryTransition revert(UUID entryId, String providerOrderId, Instant now) {
        return transition(entryId, entries.revert(entryId, providerOrderId, now));
    }

    private EntryTransition transition(UUID entryId, int updated) {
        DrawEntryStatus status = entries.findStatus(entryId).map(DrawEntryStatus::valueOf)
                .orElseThrow(() -> new IllegalArgumentException("응모가 없다: " + entryId));
        return new EntryTransition(updated == 1, status);
    }

    /** 같은 (회차, 회원) 응모가 이미 있는가 — 벤더 코드(1062)와 제약 이름으로 판정한다. 다른 제약 위반이면 그대로 던진다. */
    static boolean isEntryTaken(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                return violation.getErrorCode() == MYSQL_DUPLICATE_KEY
                        && name != null && (name.equals(ENTRY_KEY) || name.endsWith("." + ENTRY_KEY));
            }
        }
        return false;
    }

    private static DrawEntry toDomain(DrawEntryJpaEntity e) {
        return new DrawEntry(e.getId(), e.getCampaignId(), e.getCustomerId(), e.getStatus(), e.getAuthorizingProviderOrderId(),
                new ShipTo(e.getShipToName(), e.getShipToPhone(), e.getShipToPostalCode(), e.getShipToLine1(), e.getShipToLine2()),
                e.getCreatedAt());
    }
}
