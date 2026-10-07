package com.grandis.nova.common.testing.fixture.longid;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class LongIdEntity {

    @Id
    private Long id;
}
