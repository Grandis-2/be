package com.grandis.nova.order.stock.admin;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiError;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.stock.domain.enums.SaleMode;
import com.grandis.nova.order.stock.domain.exception.StockAlreadyCreatedException;
import com.grandis.nova.order.stock.domain.exception.StockBelowCommittedException;
import com.grandis.nova.order.stock.domain.model.StockSetting;
import com.grandis.nova.order.stock.domain.repository.CatalogOptions;
import com.grandis.nova.order.stock.domain.repository.StockReader;
import com.grandis.nova.order.web.ValidationFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 관리자 재고 조회 · 설정 · 초기화(order 의 유스케이스). 대상은 일반 판매 상품의 옵션이고, 요청 하나의 옵션은 모두 되거나 모두 안 된다.
 *
 * 한 트랜잭션: 상품 판매 방식 확인 → 옵션 소속 확인 → 원장 반영 → 결과 재조회.
 * 상품 · 옵션은 catalog 표를 잠그지 않고 읽는다({@link CatalogOptions} 의 전제).
 *
 * - 트랜잭션은 여기서 직접 연다. 원장 예외 뒤의 다시 하기가 새 트랜잭션이어야 해서, 바깥 트랜잭션 안에서 부르면 바로 실패한다.
 * - 다시 하기는 둘만: 같은 옵션의 행을 동시에 처음 만든 경우(PK 중복 — 다음 시도에서 그 행은 "있는 행"이다)와
 *   교착(MySQL 1213 — 상대가 이미 끝나 있다). 요청 스레드라 시도 사이에 기다리지 않고, 끝내 실패하면 503 이다.
 * - 잠금 대기 초과(1205)는 다시 하지 않고 바로 503 이다. 한 번이 이미 innodb_lock_wait_timeout(기본 50초)만큼 기다린
 *   것이라, 다시 하면 응답이 그 배수로 늘 뿐이다. (주문 생성 PlaceOrderService 는 아직 둘을 가리지 않는다 — 별도 결정)
 */
@Service
public class AdminStockService {

    private static final Logger log = LoggerFactory.getLogger(AdminStockService.class);

    static final int MAX_ATTEMPTS = 3;

    /** ER_LOCK_DEADLOCK */
    static final int MYSQL_DEADLOCK = 1213;

    private final StockLedger ledger;
    private final StockReader stockReader;
    private final CatalogOptions catalog;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public AdminStockService(StockLedger ledger, StockReader stockReader, CatalogOptions catalog,
                             PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.stockReader = stockReader;
        this.catalog = catalog;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * 그 상품 옵션 전부의 재고, option_id 오름차순. 재고 행이 없는 옵션(아직 넣지 않음 — 구매 화면에서는 품절)도 싣는다.
     * 사전예약 상품은 거절하지 않고 "재고를 세지 않음" 과 빈 목록으로 답한다 — 관리자 화면이 상품마다 이 탭을 부른다.
     *
     * @throws BusinessException PRODUCT_NOT_FOUND
     */
    public StockOverview find(Long productId) {
        return readTransaction.execute(status -> {
            SaleMode saleMode = catalog.findSaleMode(productId)
                    .orElseThrow(() -> new BusinessException(OrderErrorCode.PRODUCT_NOT_FOUND));
            if (saleMode != SaleMode.IN_STOCK) {
                return StockOverview.untracked(productId);
            }
            List<Long> optionIds = catalog.findOptionIds(productId);
            return StockOverview.of(productId, optionIds, stockReader.findByOptionIds(optionIds));
        });
    }

    /**
     * 총량을 설정한다. 행이 없는 옵션은 만든다. 목록에 없는 옵션은 건드리지 않는다.
     *
     * @throws BusinessException PRODUCT_NOT_FOUND · STOCK_NOT_TRACKED · VALIDATION_FAILED(같은 옵션이 두 번 · 그 상품의 옵션이 아님) ·
     *                           STOCK_BELOW_COMMITTED · DEPENDENCY_UNAVAILABLE(경합이 이어짐)
     */
    public StockResult set(Long productId, List<StockSetting> settings) {
        return write(productId, settings, () -> ledger.set(settings));
    }

    /**
     * 행이 없는 옵션만 만든다. 있는 옵션은 값이 달라도 그대로 두고 결과에 현재 값을 싣는다(created=false).
     * 상품 등록의 재고 단계가 부른다 — 응답을 못 받고 재개해도 그사이 관리자가 고친 값을 덮지 않는다.
     *
     * @throws BusinessException PRODUCT_NOT_FOUND · STOCK_NOT_TRACKED · VALIDATION_FAILED(같은 옵션이 두 번 · 그 상품의 옵션이 아님) ·
     *                           DEPENDENCY_UNAVAILABLE(경합이 이어짐)
     */
    public StockResult initialize(Long productId, List<StockSetting> settings) {
        return write(productId, settings, () -> ledger.initialize(settings));
    }

    private StockResult write(Long productId, List<StockSetting> settings, Supplier<Set<Long>> change) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("재고 변경은 트랜잭션 밖에서 불러야 한다 — 다시 하기가 새 트랜잭션이어야 한다");
        }
        List<Long> optionIds = settings.stream().map(StockSetting::optionId).toList();
        requireDistinctOptions(optionIds);
        for (int attempt = 1; ; attempt++) {
            try {
                return writeTransaction.execute(status -> {
                    requireStockedProduct(productId);
                    requireOwnedOptions(productId, optionIds);
                    Set<Long> created = change.get();
                    return new StockResult(productId, stockReader.findByOptionIds(optionIds), created);
                });
            } catch (StockBelowCommittedException e) {
                throw new BusinessException(OrderErrorCode.STOCK_BELOW_COMMITTED, Map.of("options",
                        e.shortfalls().stream()
                                .map(s -> Map.of("optionId", s.optionId(), "committed", s.committed()))
                                .toList()));
            } catch (StockAlreadyCreatedException | PessimisticLockingFailureException e) {
                if (e instanceof PessimisticLockingFailureException && !isDeadlock(e)) {
                    log.warn("재고 변경 잠금 대기 초과, 다시 하지 않음 productId={}", productId, e);
                    throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("재고 변경 경합 {}회, 포기 productId={}", MAX_ATTEMPTS, productId, e);
                    throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                log.info("재고 변경 경합, 다시 시도 {}/{} productId={}: {}", attempt, MAX_ATTEMPTS, productId,
                        e.getClass().getSimpleName());
            }
        }
    }

    /**
     * MySQL 교착(1213)인가. 원인 사슬의 벤더 코드로 가른다 — 재고 쓰기 경로에서 1213 과 1205 는 같은 Spring 예외
     * (CannotAcquireLockException)와 같은 SQLState(40001)로 올라와 예외 클래스로도 SQLState 로도 못 가른다
     * (LockFailureClassificationTest 가 진짜 오류로 확인).
     */
    static boolean isDeadlock(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            // 일괄 실행(BatchUpdateException 등)은 원인을 getNextException 사슬에 단다
            for (SQLException sql = t instanceof SQLException s ? s : null; sql != null; sql = sql.getNextException()) {
                if (sql.getErrorCode() == MYSQL_DEADLOCK) {
                    return true;
                }
            }
        }
        return false;
    }

    private void requireStockedProduct(Long productId) {
        SaleMode saleMode = catalog.findSaleMode(productId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.PRODUCT_NOT_FOUND));
        if (saleMode != SaleMode.IN_STOCK) {
            throw new BusinessException(OrderErrorCode.STOCK_NOT_TRACKED);
        }
    }

    /**
     * 같은 옵션이 두 번 오면 어느 값을 쓸지 모르므로 400 이다. 어느 경로로 불리든 여기서 막는다 — 원장까지 가면
     * 같은 행을 두 번 INSERT 해 PK 중복이 나고, 동시 생성으로 오인해 다시 하다 503 이 된다.
     */
    private static void requireDistinctOptions(List<Long> optionIds) {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < optionIds.size(); i++) {
            if (!seen.add(optionIds.get(i))) {
                throw ValidationFailures.of("items[%d].optionId".formatted(i), "같은 옵션이 두 번 있습니다.");
            }
        }
    }

    /** 없는 옵션 · 다른 상품의 옵션은 계약 오류라 400 이다. 요청 순서의 위치(items[i])로 모두 알린다. */
    private void requireOwnedOptions(Long productId, List<Long> optionIds) {
        Set<Long> owned = catalog.findOwnedOptionIds(productId, optionIds);
        List<ApiError.Violation> violations = new ArrayList<>();
        for (int i = 0; i < optionIds.size(); i++) {
            if (!owned.contains(optionIds.get(i))) {
                violations.add(new ApiError.Violation("items[%d].optionId".formatted(i), "이 상품의 옵션이 아닙니다."));
            }
        }
        if (!violations.isEmpty()) {
            throw ValidationFailures.of(violations);
        }
    }
}
