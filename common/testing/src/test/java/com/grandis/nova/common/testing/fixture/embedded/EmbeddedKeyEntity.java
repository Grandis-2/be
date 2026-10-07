package com.grandis.nova.common.testing.fixture.embedded;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;

import java.io.Serializable;
import java.util.UUID;

/** @EmbeddedId 복합키: 키 클래스의 UUID 칸을 센다. */
@Entity
public class EmbeddedKeyEntity {

    @EmbeddedId
    private Key key;

    @Embeddable
    public static class Key implements Serializable {
        private UUID ownerId;
        private UUID partId;
    }
}
