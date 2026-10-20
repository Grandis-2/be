package com.grandis.nova.order.draw;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.client.catalog.CatalogOption;
import com.grandis.nova.order.client.catalog.CatalogReader;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.jpa.JpaSystemException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 회차 만들기의 시간 예산 — catalog 조회를 시작한 때부터 커밋까지. 시계를 단계마다 옮겨, 예산을 넘긴 생성은 커밋하지 않고(롤백) 409 retryable 인지 본다.
 */
@DisplayName("드로우 회차 만들기 — catalog 조회부터 커밋까지 시간 예산")
class AdminDrawBudgetTest {

    static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    static final Duration BUDGET = Duration.ofSeconds(60);

    /** 시험이 옮기는 시계. */
    static final class MovableClock extends Clock {
        volatile Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final CatalogReader catalog = mock(CatalogReader.class);
    final StockLedger stock = mock(StockLedger.class);
    final DrawCampaignStore campaigns = mock(DrawCampaignStore.class);
    final NoOpTransactionManager transactions = new NoOpTransactionManager();
    final MovableClock clock = new MovableClock();
    final AdminDrawService service = new AdminDrawService(catalog, stock, campaigns, transactions, clock, BUDGET);
    final DrawCampaign draw = new DrawCampaign(TestIds.id(3), TestIds.id(1), TestIds.id(2), "t", "p", null, "o", "https://img/products/a.jpg",
            new BigDecimal("100"), 1, NOW, NOW.plusSeconds(3600), NOW);

    @BeforeEach
    void setUp() {
        given(catalog.find(any(), any())).willReturn(Map.of(TestIds.id(2), new CatalogOption(TestIds.id(2), TestIds.id(1), "p", "M-1", "o", "SKU",
                new BigDecimal("1000"), "ACTIVE", "IN_STOCK", "ACTIVE", false, true, new CatalogOption.Warranty(false, BigDecimal.ZERO),
                "https://img/products/a.jpg", null)));
        given(campaigns.insert(any())).willReturn(draw);
    }

    @Test
    @DisplayName("대조군 — 예산 안이면 커밋하고 만든다")
    void withinBudgetCommits() {
        assertThat(service.create(null, "k-1", command()).created()).isTrue();
        assertThat(transactions.commits).isEqualTo(1);
        assertThat(transactions.rollbacks).isZero();
    }

    @Test
    @DisplayName("catalog 조회에서 예산을 다 쓰면 저장 트랜잭션을 시작하지 않는다 — 409 retryable, 재고 확보 없음")
    void budgetSpentOnCatalogReadNeverStartsTheTransaction() {
        given(catalog.find(any(), any())).willAnswer(invocation -> {
            clock.now = NOW.plus(BUDGET);
            return Map.of(TestIds.id(2), new CatalogOption(TestIds.id(2), TestIds.id(1), "p", "M-1", "o", "SKU", new BigDecimal("1000"), "ACTIVE",
                    "IN_STOCK", "ACTIVE", false, true, new CatalogOption.Warranty(false, BigDecimal.ZERO), "https://img/products/a.jpg", null));
        });

        assertBudgetExceeded();
        verify(stock, never()).reserve(anyMap());
        assertThat(transactions.commits).isZero();
    }

    @Test
    @DisplayName("저장 중(재고 확보 · INSERT 사이)에 예산을 넘기면 커밋 직전 확인이 롤백한다 — 409 retryable, 커밋 0")
    void budgetExceededInsideTheTransactionRollsBack() {
        willAnswer(invocation -> {
            clock.now = NOW.plus(BUDGET);  // 확보는 됐지만 이 시점에 예산이 끝났다(잠금 대기 등)
            return null;
        }).given(stock).reserve(anyMap());

        assertBudgetExceeded();
        assertThat(transactions.commits).isZero();
        assertThat(transactions.rollbacks).isEqualTo(1);
    }

    @Test
    @DisplayName("예산 경계 — 예산 1µs 전 커밋은 되고, 정확히 예산 시각이면 롤백")
    void boundaryIsExclusive() {
        willAnswer(invocation -> {
            clock.now = NOW.plus(BUDGET).minusNanos(1_000);
            return null;
        }).given(stock).reserve(anyMap());
        assertThat(service.create(null, "k-1", command()).created()).isTrue();

        clock.now = NOW;
        willAnswer(invocation -> {
            clock.now = NOW.plus(BUDGET);
            return null;
        }).given(stock).reserve(anyMap());
        assertBudgetExceeded();
    }

    @Test
    @DisplayName("예산을 넘긴 뒤의 DB · 트랜잭션 실패는 예외 종류와 상관없이 409 retryable(상한 직전 잠금 뒤 INSERT 준비의 JpaSystemException 등), 예산 안의 같은 실패는 그대로 던진다")
    void failureAfterDeadlineIsBudgetExceeded() {
        given(campaigns.insert(any())).willAnswer(invocation -> {
            clock.now = NOW.plus(BUDGET);
            throw new JpaSystemException(new RuntimeException("Transaction timeout expired"));
        });
        assertBudgetExceeded();

        clock.now = NOW;
        willThrow(new JpaSystemException(new RuntimeException("boom"))).given(campaigns).insert(any());
        assertThatThrownBy(() -> service.create(null, "k-2", command())).isInstanceOf(JpaSystemException.class);
    }

    @Test
    @DisplayName("기한 뒤의 잠금 실패도 409 retryable — 대기 초과(1205) · 교착(1213), 기한 안의 대기 초과는 지금처럼 503")
    void lockFailureAfterDeadlineIsBudgetExceeded() {
        for (int vendorCode : new int[]{1205, 1213}) {
            clock.now = NOW;
            willAnswer(invocation -> {
                clock.now = NOW.plus(BUDGET);
                throw new CannotAcquireLockException("lock", new SQLException("lock failure", "40001", vendorCode));
            }).given(stock).reserve(anyMap());
            assertBudgetExceeded();
        }
        clock.now = NOW;
        willThrow(new CannotAcquireLockException("lock", new SQLException("lock failure", "40001", 1205))).given(stock).reserve(anyMap());
        assertThatThrownBy(() -> service.create(null, "k-1", command())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(com.grandis.nova.common.CommonErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    @Test
    @DisplayName("기한 뒤의 재고 부족은 409 INSUFFICIENT_STOCK 그대로 — 시간 탓이 아닌 실제 상태라 retryable 로 바꾸지 않는다")
    void stockShortageAfterDeadlineStaysInsufficientStock() {
        willAnswer(invocation -> {
            clock.now = NOW.plus(BUDGET);
            throw new com.grandis.nova.order.stock.domain.exception.StockShortageException(java.util.List.of(TestIds.id(2)));
        }).given(stock).reserve(anyMap());
        assertThatThrownBy(() -> service.create(null, "k-4", command())).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(OrderErrorCode.INSUFFICIENT_STOCK);
            assertThat(e.details()).doesNotContainKey("retryable");
        });
    }

    @Test
    @DisplayName("기한 뒤라도 DB · 트랜잭션 예외가 아닌 실패(코드 결함)는 409 로 덮지 않고 그대로 던진다")
    void nonDataFailureAfterDeadlineIsNotHidden() {
        willAnswer(invocation -> {
            clock.now = NOW.plus(BUDGET);
            throw new IllegalStateException("bug");
        }).given(campaigns).insert(any());
        assertThatThrownBy(() -> service.create(null, "k-3", command())).isInstanceOf(IllegalStateException.class).hasMessage("bug");
    }

    @Test
    @DisplayName("예산은 0 보다 크고 10분 이하여야 뜬다")
    void budgetMustBeInRange() {
        assertThatThrownBy(() -> AdminDrawService.requireBudget(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminDrawService.requireBudget(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminDrawService.requireBudget(Duration.ofMinutes(10).plusNanos(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(AdminDrawService.requireBudget(Duration.ofMinutes(10))).isEqualTo(Duration.ofMinutes(10));
    }

    private void assertBudgetExceeded() {
        assertThatThrownBy(() -> service.create(null, "k-1", command())).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(OrderErrorCode.STATE_CONFLICT);
            assertThat(e.details()).containsEntry("retryable", true);
        });
    }

    private static AdminDrawService.CreateDraw command() {
        return new AdminDrawService.CreateDraw(TestIds.id(1), TestIds.id(2), "t", new BigDecimal("100"), 1, NOW, NOW.plusSeconds(3600));
    }
}
