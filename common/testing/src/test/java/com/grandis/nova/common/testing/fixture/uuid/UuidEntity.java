package com.grandis.nova.common.testing.fixture.uuid;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

@Entity
public class UuidEntity {

    @Id
    private UUID id;
}
