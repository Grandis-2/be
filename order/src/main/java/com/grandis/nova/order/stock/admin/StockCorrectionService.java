package com.grandis.nova.order.stock.admin;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.MySqlLockFailures;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.stock.domain.exception.StockTotalBelowExpectedException;
import com.grandis.nova.order.stock.domain.model.StockAdjustment;
import com.grandis.nova.order.stock.domain.model.StockBalance;
import com.grandis.nova.order.stock.domain.model.StockCorrection;
import com.grandis.nova.order.stock.domain.repository.CatalogOptions;
import com.grandis.nova.order.stock.domain.repository.StockAdjustments;
import com.grandis.nova.order.stock.domain.repository.StockExpectations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * 관리자 "주문 기준으로 다시 맞추기" — 옵션 하나의 확보 · 판매를 주문 기준 수량으로 덮는다(총량은 그대로). 숫자를 직접 넣는 길은 두지 않는다 —
 * 주문이 진실이고, 장부는 그것을 따라간다.
 *
 * - 미리보기: 잠그지 않고 SQL 한 문장으로 장부와 주문 기준을 함께 읽는다(그 시점에서 정확하다).
 * - 보정(한 트랜잭션): 상품 · 옵션 확인 → 원장 보정({@link StockLedger#correct} — 재고 행을 잠근 뒤 주문 기준을 읽어 덮는다) → 바뀌었으면 기록.
 * - 트랜잭션은 여기서 직접 연다(AdminStockService 와 같다). 다시 하기는 교착(MySQL 1213)만 최대 {@link #MAX_ATTEMPTS}번이고, 잠금 대기 초과
 *   (1205)는 바로 503 이다 — 한 번이 이미 innodb_lock_wait_timeout 만큼 기다린 것이다.
 */
@Service
public class StockCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(StockCorrectionService.class);

    static final int MAX_ATTEMPTS = 3;

    private final StockLedger ledger;
    private final StockExpectations expectations;
    private final StockAdjustments adjustments;
    private final StockTargets targets;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final Clock clock;

    public StockCorrectionService(StockLedger ledger, StockExpectations expectations, StockAdjustments adjustments, CatalogOptions catalog,
                                  PlatformTransactionManager transactionManager, Clock clock) {
        this.ledger = ledger;
        this.expectations = expectations;
        this.adjustments = adjustments;
        this.targets = new StockTargets(catalog);
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
    }

    /** @throws BusinessException PRODUCT_NOT_FOUND · STOCK_NOT_TRACKED · VALIDATION_FAILED(그 상품의 옵션이 아님) */
    public StockBalance preview(UUID productId, UUID optionId) {
        return readTransaction.execute(status -> {
            targets.requireStockedProduct(productId);
            targets.requireOwnedOption(productId, optionId);
            return expectations.balanceOf(optionId);
        });
    }

    /**
     * @param reason      관리자 사유(검증한 것 — 500자 이하)
     * @param requestedBy 관리자의 인증 주체 이름
     * @throws BusinessException PRODUCT_NOT_FOUND · STOCK_NOT_TRACKED · VALIDATION_FAILED(그 상품의 옵션이 아님) · STOCK_TOTAL_BELOW_EXPECTED ·
     *                           DEPENDENCY_UNAVAILABLE(교착이 이어짐 · 잠금 대기 초과)
     */
    public StockCorrection correct(UUID productId, UUID optionId, String reason, String requestedBy) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("재고 보정은 트랜잭션 밖에서 불러야 한다 — 다시 하기가 새 트랜잭션이어야 한다");
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return writeTransaction.execute(status -> {
                    targets.requireStockedProduct(productId);
                    targets.requireOwnedOption(productId, optionId);
                    StockCorrection correction = ledger.correct(optionId);
                    if (correction.changed()) {
                        adjustments.record(new StockAdjustment(correction, reason, requestedBy, clock.instant()));
                        log.warn("재고 보정 optionId={} reserved {}→{} sold {}→{} total={} requestedBy={}", optionId, correction.beforeReserved(),
                                correction.afterReserved(), correction.beforeSold(), correction.afterSold(), correction.total(), requestedBy);
                    }
                    return correction;
                });
            } catch (StockTotalBelowExpectedException e) {
                throw new BusinessException(OrderErrorCode.STOCK_TOTAL_BELOW_EXPECTED, Map.of(
                        "optionId", e.optionId().toString(), "registered", e.registered(), "total", e.total(),
                        "expectedReserved", e.expectedReserved(), "expectedSold", e.expectedSold()));
            } catch (PessimisticLockingFailureException e) {
                if (!MySqlLockFailures.isDeadlock(e)) {
                    log.warn("재고 보정 잠금 대기 초과, 다시 하지 않음 optionId={}", optionId, e);
                    throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("재고 보정 교착 {}회, 포기 optionId={}", MAX_ATTEMPTS, optionId, e);
                    throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                log.info("재고 보정 교착, 다시 시도 {}/{} optionId={}", attempt, MAX_ATTEMPTS, optionId);
            }
        }
    }
}
