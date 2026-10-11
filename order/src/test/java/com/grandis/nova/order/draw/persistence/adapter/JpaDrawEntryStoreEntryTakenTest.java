package com.grandis.nova.order.draw.persistence.adapter;

import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 응모 중복 판정은 1062 와 (회차, 회원) 유일 키 이름 둘 다로 한다. 실제 MySQL 의 이름 모양("표.키")은 DrawEntryApiTest 의 겹친 응모 시험이 본다.
 */
class JpaDrawEntryStoreEntryTakenTest {

    @Test
    void onlyDuplicateOfTheMemberKeyIsEntryTaken() {
        assertThat(JpaDrawEntryStore.isEntryTaken(wrapped(1062, "draw_entries.uq_draw_entry_customer"))).isTrue();
        assertThat(JpaDrawEntryStore.isEntryTaken(wrapped(1062, "uq_draw_entry_customer"))).isTrue();

        assertThat(JpaDrawEntryStore.isEntryTaken(wrapped(1062, "draw_entries.PRIMARY"))).as("기본 키 충돌").isFalse();
        assertThat(JpaDrawEntryStore.isEntryTaken(wrapped(1062, null))).as("이름을 모름").isFalse();
        assertThat(JpaDrawEntryStore.isEntryTaken(wrapped(1452, "draw_entries.uq_draw_entry_customer"))).as("1062 가 아님").isFalse();
        assertThat(JpaDrawEntryStore.isEntryTaken(new PersistenceException("x"))).isFalse();
    }

    private static PersistenceException wrapped(int vendorCode, String constraint) {
        return new PersistenceException("x", new ConstraintViolationException("x", new SQLException("x", "23000", vendorCode), constraint));
    }
}
