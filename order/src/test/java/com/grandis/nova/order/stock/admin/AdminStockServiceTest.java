package com.grandis.nova.order.stock.admin;

import com.grandis.nova.order.MySqlLockFailures;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiError;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.stock.domain.enums.SaleMode;
import com.grandis.nova.order.stock.domain.exception.StockAlreadyCreatedException;
import com.grandis.nova.order.stock.domain.exception.StockBelowCommittedException;
import com.grandis.nova.order.stock.domain.exception.StockBelowCommittedException.Shortfall;
import com.grandis.nova.order.stock.domain.model.StockSetting;
import com.grandis.nova.order.stock.domain.repository.CatalogOptions;
import com.grandis.nova.order.stock.domain.repository.StockReader;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 판정 순서 · 다시 하기 · 예외 번역. DB 동작은 StockLedgerTest · AdminStockApiTest 가 MySQL 로 본다. */
class AdminStockServiceTest {

    static final UUID PRODUCT_ID = TestIds.id(1);
    static final List<StockSetting> SETTINGS = List.of(new StockSetting(TestIds.id(11), 5), new StockSetting(TestIds.id(12), 0));

    StockLedger ledger = mock(StockLedger.class);
    StockReader reader = mock(StockReader.class);
    CatalogOptions catalog = mock(CatalogOptions.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    AdminStockService service = new AdminStockService(ledger, reader, catalog, transactionManager);

    @BeforeEach
    void setUp() {
        given(transactionManager.getTransaction(any())).willAnswer(invocation -> new SimpleTransactionStatus());
        given(catalog.findSaleMode(PRODUCT_ID)).willReturn(Optional.of(SaleMode.IN_STOCK));
        given(catalog.findOwnedOptionIds(PRODUCT_ID, List.of(TestIds.id(11), TestIds.id(12)))).willReturn(Set.of(TestIds.id(11), TestIds.id(12)));
        given(reader.findByOptionIds(any())).willReturn(List.of());
    }

    @Test
    void concurrentCreationIsRetriedInNewTransaction() {
        given(ledger.set(SETTINGS))
                .willThrow(new StockAlreadyCreatedException(TestIds.id(11), null))
                .willReturn(Set.of());

        service.set(PRODUCT_ID, SETTINGS);

        verify(ledger, times(2)).set(SETTINGS);
        verify(transactionManager, times(2)).getTransaction(any());
    }

    @Test
    void initializeUsesLedgerInitializeWithTheSameRetry() {
        given(ledger.initialize(SETTINGS))
                .willThrow(new StockAlreadyCreatedException(TestIds.id(11), null))
                .willReturn(Set.of());

        service.initialize(PRODUCT_ID, SETTINGS);

        verify(ledger, times(2)).initialize(SETTINGS);
        verify(ledger, never()).set(anyList());
    }

    @Test
    void repeatedCreationRaceGivesUpAfterMaxAttempts() {
        given(ledger.set(SETTINGS)).willThrow(new StockAlreadyCreatedException(TestIds.id(11), null));

        assertThatThrownBy(() -> service.set(PRODUCT_ID, SETTINGS))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        verify(ledger, times(AdminStockService.MAX_ATTEMPTS)).set(SETTINGS);
    }

    // 일괄 실행은 벤더 오류를 원인이 아니라 getNextException 사슬에 단다.
    @Test
    void deadlockIsFoundInNextExceptionChain() {
        BatchUpdateException batch = new BatchUpdateException("batch failed", "40001", 0, new int[0]);
        batch.setNextException(new SQLException("Deadlock found", "40001", MySqlLockFailures.MYSQL_DEADLOCK));

        assertThat(MySqlLockFailures.isDeadlock(new CannotAcquireLockException("batch", batch))).isTrue();
        assertThat(MySqlLockFailures.isDeadlock(new CannotAcquireLockException("timeout",
                new SQLException("Lock wait timeout exceeded", "40001", 1205)))).isFalse();
    }

    @Test
    void deadlockIsRetriedInNewTransaction() {
        given(ledger.set(SETTINGS)).willThrow(deadlock()).willReturn(Set.of());

        service.set(PRODUCT_ID, SETTINGS);

        verify(ledger, times(2)).set(SETTINGS);
    }

    @Test
    void deadlockGivesUpAfterMaxAttempts() {
        given(ledger.set(SETTINGS)).willThrow(deadlock());

        assertThatThrownBy(() -> service.set(PRODUCT_ID, SETTINGS))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        verify(ledger, times(AdminStockService.MAX_ATTEMPTS)).set(SETTINGS);
    }

    // 한 번이 이미 innodb_lock_wait_timeout 만큼 기다렸다. 다시 하면 응답이 그 배수로 늘 뿐이다.
    @Test
    void lockWaitTimeoutIsNotRetried() {
        given(ledger.set(SETTINGS)).willThrow(new CannotAcquireLockException("Lock wait timeout exceeded",
                new SQLException("Lock wait timeout exceeded", "40001", 1205)));

        assertThatThrownBy(() -> service.set(PRODUCT_ID, SETTINGS))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        verify(ledger, times(1)).set(SETTINGS);
    }

    @Test
    void belowCommittedIsNotRetriedAndListsEveryOption() {
        given(ledger.set(SETTINGS)).willThrow(new StockBelowCommittedException(
                List.of(new Shortfall(TestIds.id(11), 6), new Shortfall(TestIds.id(12), 1))));

        assertThatThrownBy(() -> service.set(PRODUCT_ID, SETTINGS))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(OrderErrorCode.STOCK_BELOW_COMMITTED);
                    assertThat(e.details()).isEqualTo(Map.of("options", List.of(
                            Map.of("optionId", TestIds.id(11), "committed", 6), Map.of("optionId", TestIds.id(12), "committed", 1))));
                });
        verify(ledger, times(1)).set(SETTINGS);
    }

    @Test
    void productChecksComeBeforeAnyWrite() {
        given(catalog.findSaleMode(PRODUCT_ID)).willReturn(Optional.of(SaleMode.PREORDER));
        assertThatThrownBy(() -> service.set(PRODUCT_ID, SETTINGS))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(OrderErrorCode.STOCK_NOT_TRACKED));

        given(catalog.findSaleMode(PRODUCT_ID)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.set(PRODUCT_ID, SETTINGS))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(OrderErrorCode.PRODUCT_NOT_FOUND));

        verify(ledger, never()).set(anyList());
    }

    // 원장까지 가면 PK 중복이 동시 생성으로 오인돼 다시 하다 503 이 된다. 트랜잭션을 열기 전에 400 으로 끊는다.
    @Test
    void sameOptionTwiceIsRejectedBeforeAnyTransaction() {
        List<StockSetting> twice = List.of(new StockSetting(TestIds.id(11), 5), new StockSetting(TestIds.id(12), 1),
                new StockSetting(TestIds.id(11), 3));

        assertThatThrownBy(() -> service.initialize(PRODUCT_ID, twice))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
                    assertThat(e.details()).isEqualTo(Map.of("violations",
                            List.of(new ApiError.Violation("items[2].optionId", "같은 옵션이 두 번 있습니다."))));
                });
        assertThatThrownBy(() -> service.set(PRODUCT_ID, twice)).isInstanceOf(BusinessException.class);

        verify(transactionManager, never()).getTransaction(any());
        verify(ledger, never()).initialize(anyList());
        verify(ledger, never()).set(anyList());
    }

    // 바깥 트랜잭션에 참여하면 원장 예외 뒤의 다시 하기가 rollback-only 인 같은 트랜잭션에서 일어난다.
    @Test
    void refusesToRunInsideTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.set(PRODUCT_ID, SETTINGS)).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(ledger, never()).set(anyList());
    }

    private static CannotAcquireLockException deadlock() {
        return new CannotAcquireLockException("Deadlock found",
                new SQLException("Deadlock found when trying to get lock", "40001", MySqlLockFailures.MYSQL_DEADLOCK));
    }
}
