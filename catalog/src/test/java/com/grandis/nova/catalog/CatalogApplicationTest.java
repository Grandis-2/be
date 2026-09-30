package com.grandis.nova.catalog;

import com.grandis.nova.catalog.config.JpaAuditingConfig;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

@CatalogIntegrationTest
class CatalogApplicationTest {

    @Autowired Clock clock;

    /**
     * 앱이 실제로 쓰는 시계가 마이크로초로 내린 것인지 빈 자체를 비교한다. macOS 시계는 원래 마이크로초까지만 줘서
     * 저장 → 재조회 같은 행동 시험은 여기서 늘 통과하므로(반대 구현을 못 잡는다) 빈을 본다.
     * common:security 가 같은 이름의 clock 빈을 들여와 이 시계를 밀어내면 여기서 떨어진다(order 선례).
     */
    @Test
    @DisplayName("앱 시계는 저장 해상도(마이크로초)로 내린 UTC 시계다")
    void applicationClockIsAtStorageResolution() {
        assertThat(clock).isEqualTo(JpaAuditingConfig.atStorageResolution(Clock.systemUTC()));
    }
}
