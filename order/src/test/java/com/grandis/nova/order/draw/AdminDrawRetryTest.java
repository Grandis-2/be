package com.grandis.nova.order.draw;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.MySqlLockFailures;
import com.grandis.nova.order.client.catalog.CatalogOption;
import com.grandis.nova.order.client.catalog.CatalogReader;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 회차 만들기의 잠금 실패 가르기 — 교착만 새 트랜잭션에서 다시(최대 {@link AdminDrawService#MAX_ATTEMPTS}), 잠금 대기 초과는 다시 하지 않고,
 * 다 써도 503 이다. 다만 그 사이 같은 키의 회차가 들어갔으면 그것을 돌려준다.
 */
class AdminDrawRetryTest {

    static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    static final UUID PRODUCT = TestIds.id(1);
    static final UUID OPTION = TestIds.id(2);
    static final String KEY = "k-1";

    final CatalogReader catalog = mock(CatalogReader.class);
    final StockLedger stock = mock(StockLedger.class);
    final DrawCampaignStore campaigns = mock(DrawCampaignStore.class);
    final AdminDrawService service = new AdminDrawService(catalog, stock, campaigns, mock(PlatformTransactionManager.class),
            Clock.fixed(NOW, ZoneOffset.UTC));
    final DrawCampaign draw = new DrawCampaign(TestIds.id(3), PRODUCT, OPTION, "t", "p", "o", null, new BigDecimal("100"), 1, NOW,
            NOW.plusSeconds(600), NOW);

    @BeforeEach
    void setUp() {
        given(catalog.find(any(), any())).willReturn(Map.of(OPTION, new CatalogOption(OPTION, PRODUCT, "p", "o", "SKU", new BigDecimal("1000"),
                "ACTIVE", "IN_STOCK", "ACTIVE", false, true, new CatalogOption.Warranty(false, BigDecimal.ZERO), null)));
        given(campaigns.insert(any())).willReturn(draw);
    }

    @Test
    void deadlockIsRetriedOnce() {
        willThrow(lockFailure(MySqlLockFailures.MYSQL_DEADLOCK)).willDoNothing().given(stock).reserve(anyMap());

        AdminDrawService.Created created = service.create(null, KEY, command());

        assertThat(created.created()).isTrue();
        assertThat(created.campaign()).isEqualTo(draw);
        verify(stock, times(2)).reserve(anyMap());
    }

    @Test
    void exhaustedDeadlockIsUnavailable() {
        willThrow(lockFailure(MySqlLockFailures.MYSQL_DEADLOCK)).given(stock).reserve(anyMap());

        assertThatThrownBy(() -> service.create(null, KEY, command())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        verify(stock, times(AdminDrawService.MAX_ATTEMPTS)).reserve(anyMap());
    }

    @Test
    void lockWaitTimeoutIsNotRetried() {
        willThrow(lockFailure(1205)).given(stock).reserve(anyMap());

        assertThatThrownBy(() -> service.create(null, KEY, command())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        verify(stock, times(1)).reserve(anyMap());
    }

    @Test
    void lockFailureReturnsTheDrawThatTookTheKeyMeanwhile() {
        willThrow(lockFailure(1205)).given(stock).reserve(anyMap());
        given(campaigns.findByIdempotencyKey(KEY)).willReturn(Optional.empty(), Optional.of(draw));

        AdminDrawService.Created created = service.create(null, KEY, command());

        assertThat(created.created()).isFalse();
        assertThat(created.campaign()).isEqualTo(draw);
    }

    private static AdminDrawService.CreateDraw command() {
        return new AdminDrawService.CreateDraw(PRODUCT, OPTION, "t", new BigDecimal("100"), 1, NOW, NOW.plusSeconds(600));
    }

    private static CannotAcquireLockException lockFailure(int vendorCode) {
        return new CannotAcquireLockException("lock", new SQLException("lock failure", "40001", vendorCode));
    }
}
