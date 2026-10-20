package com.grandis.nova.order.draw;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.MySqlLockFailures;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.client.catalog.CatalogOption;
import com.grandis.nova.order.client.catalog.CatalogReader;
import com.grandis.nova.order.draw.domain.exception.DrawKeyTakenException;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.NewDrawCampaign;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.stock.domain.exception.StockShortageException;
import com.grandis.nova.order.web.ValidationFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 관리자 럭키 드로우 회차 만들기. 목록 · 상세는 {@link DrawQueryService}.
 *
 * 만들기: 같은 Idempotency-Key 의 회차가 있으면 그것(본문은 대조하지 않는다 — 상품 등록과 같은 규칙) → 트랜잭션 밖에서 catalog 에 증정품 옵션을
 * 묻고(관리자 토큰 중계) → 트랜잭션에서 당첨 인원만큼 재고를 확보하고 회차를 넣는다. 같은 새 키가 동시에 오면 유일 키가 하나만 남기고, 진 쪽은
 * 롤백(확보도)한 뒤 먼저 들어간 회차를 돌려준다 — 응답을 잃고 다시 보내도 회차 · 확보가 둘이 되지 않는다. 같은 옵션을 확보하는 경우 진 쪽은
 * 유일 키보다 먼저 재고 행에서 기다리므로, 재고가 회차 하나 몫뿐이면 키 충돌이 아니라 재고 부족으로 끝난다 — 그래서 재고 부족 · 잠금 실패에서도
 * 그 키의 회차를 다시 보고, 있으면 그것을 돌려준다. 다시 볼 때 앞선 같은 키가 아직 커밋 전이면(잠금 대기 초과 · 다른 옵션으로 바로 재고 부족)
 * 503 · 409 이고, 다시 보내면 그 회차다.
 * - 증정품은 일반 판매(IN_STOCK) · 상품 · 옵션 모두 판매 중(ACTIVE) · 재고 등록(준비 완료)인 옵션이어야 한다. **공개 여부는 보지 않는다** —
 *   매장에 안 파는 증정품은 catalog 에 비공개 상품으로 먼저 등록해 둔다.
 * - 재고는 조건부 UPDATE 로 확보한다({@link StockLedger#reserve}) — 모자라면 409 INSUFFICIENT_STOCK 이고 회차를 만들지 않는다.
 *   확보한 재고는 당첨자의 증정(0원 주문)에 쓰인다.
 * - 응모 마감은 지금보다 뒤여야 한다. 시작은 지금보다 앞이어도 된다(바로 응모 중).
 * - <b>시간 예산</b>: catalog 조회를 시작한 때부터 회차 저장의 커밋까지를 {@link #budget}(기본 60초) 안에 끝낸다. 회차는 증정품 사진 주소를
 *   스냅샷하는데, catalog 의 정리 배치는 아무 회차도 가리키지 않는 안 쓰는 사진을 지운다 — 읽어 둔 채 오래 멈춘 생성이 그 사이 빠지고 지워진
 *   사진을 저장하지 않게, 배치의 기준 시간(하한 1시간)보다 훨씬 짧은 예산을 둔다. 넘으면 롤백(확보 포함)하고 409 retryable 이다 —
 *   다시 보내면 지금 사진을 다시 읽는다.
 *   <ul>
 *     <li>예산이 보장하는 것은 커밋 직전 확인(beforeCommit)까지다 — 그 시각이 예산 안이어야 커밋으로 간다. 회차 INSERT 는 그 전에 flush 하므로
 *         확인 뒤에 남는 것은 COMMIT 문 하나다(그 문장 자체에는 시간 상한이 없다).</li>
 *     <li>저장 트랜잭션의 시간 상한(남은 예산을 초로 올림)은 스레드를 붙잡지 않게 하는 장치다 — 재고 행 잠금을 기다리는 문장이 잠금 대기 기본값(50초)
 *         대신 그 상한에서 끊긴다(실측: 예산 2초에 2.2초에 끝남). 상한이 올림이라 예산을 조금 넘겨 끊길 수 있고, 그 몫은 커밋 직전 확인이 막는다.</li>
 *     <li>예산을 넘긴 뒤의 DB · 트랜잭션 · 잠금 실패는 예외 종류와 상관없이 409 다 — 끊는 지점에 따라 QueryTimeoutException(잠금 대기 중) · JpaSystemException
 *         (Hibernate 의 "Transaction timeout expired" — 상한 직전에 잠금을 얻고 INSERT 를 준비할 때)으로 달리 온다(리뷰 실측).</li>
 *     <li>재고 부족은 기한 뒤라도 409 INSUFFICIENT_STOCK 그대로다 — 시간 탓이 아닌 실제 상태라 다시 보내도 같고(retryable 이 아니다),
 *         트랜잭션은 롤백돼 사진 주소를 저장하지 않는다.</li>
 *   </ul>
 */
@Service
public class AdminDrawService {

    private static final Logger log = LoggerFactory.getLogger(AdminDrawService.class);

    static final String IN_STOCK = "IN_STOCK";
    static final int MAX_KEY_LENGTH = 100;
    static final int MAX_ATTEMPTS = 2;
    static final String ACTIVE = "ACTIVE";
    /** 시간 예산의 상한. 정리 배치의 기준 시간 하한(1시간)과 거리를 둔다. */
    static final Duration MAX_BUDGET = Duration.ofMinutes(10);

    private final CatalogReader catalog;
    private final StockLedger stock;
    private final DrawCampaignStore campaigns;
    private final PlatformTransactionManager transactionManager;
    private final TransactionTemplate readTransaction;
    private final Clock clock;
    private final Duration budget;

    public AdminDrawService(CatalogReader catalog, StockLedger stock, DrawCampaignStore campaigns,
                            PlatformTransactionManager transactionManager, Clock clock,
                            @Value("${nova.draw.create-budget:PT60S}") Duration budget) {
        this.catalog = catalog;
        this.stock = stock;
        this.campaigns = campaigns;
        this.transactionManager = transactionManager;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
        this.budget = requireBudget(budget);
    }

    /** 0 보다 크고 10분 이하 — 정리 배치의 기준 시간 하한(1시간)보다 충분히 짧아야 한다. 0 이하면 모든 생성이 409 다. */
    static Duration requireBudget(Duration budget) {
        if (budget == null || budget.isNegative() || budget.isZero() || budget.compareTo(MAX_BUDGET) > 0) {
            throw new IllegalArgumentException("nova.draw.create-budget must be in (0, " + MAX_BUDGET + "]: " + budget);
        }
        return budget;
    }

    /**
     * @param sessionToken   관리자 액세스 토큰(catalog 에 그대로 전달). 로그 · 예외에 싣지 않는다
     * @param idempotencyKey 관리자 요청의 Idempotency-Key(1~100자) — 같은 키는 한 회차다
     * @throws BusinessException 404 PRODUCT_NOT_FOUND(옵션이 없음 · 상품과 짝이 다름) · 400 VALIDATION_FAILED(증정품 조건 · 마감이 지남) ·
     *                           409 INSUFFICIENT_STOCK · 401 · 503 · 500(catalog, {@link CatalogReader#find}) · 503(잠금 실패)
     */
    public Created create(String sessionToken, String idempotencyKey, CreateDraw command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("회차 만들기는 트랜잭션 밖에서 불러야 한다 — catalog 호출 동안 잠금을 쥐지 않고, 다시 하기가 새 트랜잭션이게");
        }
        String key = idempotencyKey == null ? "" : idempotencyKey.strip();
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            throw ValidationFailures.of("Idempotency-Key", "1~%d자여야 합니다.".formatted(MAX_KEY_LENGTH));
        }
        Optional<DrawCampaign> existing = findByKey(key);
        if (existing.isPresent()) {
            return new Created(existing.get(), false);
        }
        if (!command.closesAt().isAfter(clock.instant())) {
            throw ValidationFailures.of("closesAt", "응모 마감은 지금보다 뒤여야 합니다.");
        }
        // 예산은 사진 주소를 읽기 시작할 때부터 센다
        Instant deadline = clock.instant().plus(budget);
        CatalogOption option = catalog.find(List.of(command.optionId()), sessionToken).get(command.optionId());
        if (option == null || !option.productId().equals(command.productId())) {
            throw new BusinessException(OrderErrorCode.PRODUCT_NOT_FOUND);
        }
        requireGiftable(option);
        NewDrawCampaign draft = new NewDrawCampaign(key, option.productId(), option.optionId(), command.title(), option.productTitle(),
                option.modelName(), option.optionTitle(), option.imageUrl(), command.entryFee(), command.winnerCount(), command.opensAt(), command.closesAt());
        for (int attempt = 1; ; attempt++) {
            TransactionTemplate transaction = writeTransaction(deadline);
            try {
                return new Created(transaction.execute(status -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            if (!clock.instant().isBefore(deadline)) {
                                throw new BudgetExceededException();
                            }
                        }
                    });
                    stock.reserve(Map.of(option.optionId(), command.winnerCount()));
                    return campaigns.insert(draft);
                }), true);
            } catch (BudgetExceededException | TransactionTimedOutException | QueryTimeoutException e) {
                // 트랜잭션 상한에 끊긴 것 — 잠금 대기 중이면 QueryTimeoutException 이다(실측, AdminDrawBudgetLockWaitTest)
                log.info("드로우 회차 만들기 시간 예산 초과 optionId={} budget={}", option.optionId(), budget);
                throw budgetExceeded();
            } catch (DrawKeyTakenException e) {
                // 같은 새 키가 동시에 왔다. 이 트랜잭션(확보 포함)은 롤백됐으므로 새 트랜잭션에서 먼저 들어간 회차를 읽는다
                return new Created(findByKey(key).orElseThrow(() -> new IllegalStateException("키 충돌인데 그 키의 회차가 없다: " + key, e)),
                        false);
            } catch (StockShortageException e) {
                Optional<DrawCampaign> winner = findByKey(key);
                if (winner.isPresent()) {
                    return new Created(winner.get(), false);
                }
                throw new BusinessException(OrderErrorCode.INSUFFICIENT_STOCK, "당첨 인원만큼 재고가 없습니다.", Map.of("variantIds", e.optionIds()));
            } catch (PessimisticLockingFailureException e) {
                // 기한 뒤의 잠금 실패(대기 초과 · 교착)도 다른 실패처럼 409 — 그 사이 같은 키의 회차가 들어갔으면 그것을 돌려준다
                if (!clock.instant().isBefore(deadline)) {
                    Optional<DrawCampaign> winner = findByKey(key);
                    if (winner.isPresent()) {
                        return new Created(winner.get(), false);
                    }
                    log.warn("드로우 회차 만들기 시간 예산 초과(잠금 실패) optionId={} budget={}", option.optionId(), budget, e);
                    throw budgetExceeded();
                }
                // 재고 원장의 계약 — 동시 삽입이 부르는 S 잠금(중복 키 확인 · 외래 키 부모)의 드문 교착은 호출하는 쪽이 한 번 더 한다(장바구니 주문과 같다)
                if (!MySqlLockFailures.isDeadlock(e) || attempt >= MAX_ATTEMPTS) {
                    Optional<DrawCampaign> winner = findByKey(key);
                    if (winner.isPresent()) {
                        return new Created(winner.get(), false);
                    }
                    log.warn("드로우 회차 만들기 잠금 실패 optionId={} attempt={}", option.optionId(), attempt, e);
                    throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                log.info("드로우 회차 만들기 교착, 다시 시도 {}/{} optionId={}", attempt, MAX_ATTEMPTS, option.optionId());
            } catch (DataAccessException | TransactionException e) {
                // 예산을 넘긴 뒤의 DB · 트랜잭션 실패는 시각으로 가른다 — 상한에 끊긴 예외가 위 목록 밖 종류(JpaSystemException 등)로도 온다.
                // 그 밖의 예외(코드 결함)는 기한 뒤라도 덮지 않는다. 원인은 스택과 함께 남긴다
                if (!clock.instant().isBefore(deadline)) {
                    log.warn("드로우 회차 만들기 시간 예산 초과 optionId={} budget={}", option.optionId(), budget, e);
                    throw budgetExceeded();
                }
                throw e;
            }
        }
    }

    /** 남은 예산을 시간 상한으로 건 저장 트랜잭션. 이미 다 썼으면 시작하지 않는다. 상한은 초 단위라 올림한다 — 넘친 몫은 커밋 직전 확인이 막는다. */
    private TransactionTemplate writeTransaction(Instant deadline) {
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            log.info("드로우 회차 만들기 시간 예산 초과(저장 전) budget={}", budget);
            throw budgetExceeded();
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setTimeout((int) Math.max(1, (remaining.toMillis() + 999) / 1000));
        return template;
    }

    private static BusinessException budgetExceeded() {
        return new BusinessException(OrderErrorCode.STATE_CONFLICT, "회차를 만드는 데 시간이 너무 걸렸습니다. 다시 시도해 주세요.",
                Map.of("retryable", true));
    }

    /** 커밋 직전 확인에서 예산을 넘었다 — 트랜잭션은 롤백된다. */
    private static final class BudgetExceededException extends RuntimeException {
        BudgetExceededException() {
            super("draw create budget exceeded", null, false, false);
        }
    }

    private Optional<DrawCampaign> findByKey(String key) {
        return readTransaction.execute(status -> campaigns.findByIdempotencyKey(key));
    }

    /** 증정품이 될 수 있는 옵션인가 — 일반 판매 · 판매 중 · 재고 등록. 공개 여부는 보지 않는다(비공개 증정품). */
    private static void requireGiftable(CatalogOption option) {
        if (!IN_STOCK.equals(option.saleMode())) {
            throw ValidationFailures.of("optionId", "일반 판매 상품의 옵션만 증정품이 될 수 있습니다.");
        }
        if (!ACTIVE.equals(option.productStatus()) || !ACTIVE.equals(option.optionStatus())) {
            throw ValidationFailures.of("optionId", "판매 중인 옵션만 증정품이 될 수 있습니다.");
        }
        if (!Boolean.TRUE.equals(option.registrationCompleted())) {
            throw ValidationFailures.of("optionId", "재고를 먼저 등록해 주세요.");
        }
    }

    /** @param created 이번 요청이 만들었으면 true, 같은 Idempotency-Key 의 회차를 돌려줬으면 false */
    public record Created(DrawCampaign campaign, boolean created) {
    }

    /** 회차 만들기 입력. 형식 검사는 요청이 끝냈다(시각은 µs 로 잘렸다). */
    public record CreateDraw(UUID productId, UUID optionId, String title, BigDecimal entryFee, int winnerCount, Instant opensAt,
                             Instant closesAt) {
    }
}
