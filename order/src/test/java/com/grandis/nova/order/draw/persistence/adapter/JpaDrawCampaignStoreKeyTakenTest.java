package com.grandis.nova.order.draw.persistence.adapter;

import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 키 충돌 판정은 1062 와 제약 이름 둘 다로 한다 — 다른 유일 키(기본 키)의 1062 나 같은 이름의 다른 위반은 키 충돌이 아니다.
 * 제약 이름 모양("표.키")은 실제 MySQL 경로에서 AdminDrawApiTest 의 동시 같은 키 시험이 본다.
 */
class JpaDrawCampaignStoreKeyTakenTest {

    @Test
    void onlyDuplicateOfTheIdempotencyKeyIsKeyTaken() {
        assertThat(JpaDrawCampaignStore.isKeyTaken(wrapped(1062, "draw_campaigns.uq_draw_campaign_idempotency"))).isTrue();
        assertThat(JpaDrawCampaignStore.isKeyTaken(wrapped(1062, "uq_draw_campaign_idempotency"))).isTrue();

        assertThat(JpaDrawCampaignStore.isKeyTaken(wrapped(1062, "draw_campaigns.PRIMARY"))).as("기본 키 충돌").isFalse();
        assertThat(JpaDrawCampaignStore.isKeyTaken(wrapped(1062, "other.xuq_draw_campaign_idempotency"))).as("이름 끝만 같은 다른 키").isFalse();
        assertThat(JpaDrawCampaignStore.isKeyTaken(wrapped(1062, null))).as("이름을 모름").isFalse();
        assertThat(JpaDrawCampaignStore.isKeyTaken(wrapped(3819, "draw_campaigns.uq_draw_campaign_idempotency"))).as("1062 가 아님").isFalse();
        assertThat(JpaDrawCampaignStore.isKeyTaken(new PersistenceException("x"))).isFalse();
    }

    private static PersistenceException wrapped(int vendorCode, String constraint) {
        return new PersistenceException("x", new ConstraintViolationException("x", new SQLException("x", "23000", vendorCode), constraint));
    }
}
