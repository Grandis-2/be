package com.grandis.nova.preorder;

import com.grandis.nova.common.testing.EntityIds;
import org.junit.jupiter.api.Test;

class EntityIdTest {

    @Test
    void 엔티티_id_는_UUID_다() {
        EntityIds.assertUuidIds("com.grandis.nova.preorder");
    }
}
