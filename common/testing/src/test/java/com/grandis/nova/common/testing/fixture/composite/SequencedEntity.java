package com.grandis.nova.common.testing.fixture.composite;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/** 복합키: UUID 옆의 순번은 허용한다. */
@Entity
public class SequencedEntity {

    @Id
    private UUID ownerId;

    @Id
    private long sequence;
}
