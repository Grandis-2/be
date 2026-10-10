package com.grandis.nova.order.draw;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.OffsetPage;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 관리자 럭키 드로우 회차. 만들기 · 목록 · 상세.
 *
 * 만들기: 같은 Idempotency-Key 의 회차가 있으면 그것(본문은 대조하지 않는다 — 상품 등록과 같은 규칙) → 트랜잭션 밖에서 catalog 에 증정품 옵션을
 * 묻고(관리자 토큰 중계) → 트랜잭션에서 당첨 인원만큼 재고를 확보하고 회차를 넣는다. 같은 새 키가 동시에 오면 유일 키가 하나만 남기고, 진 쪽은
 * 롤백(확보도)한 뒤 먼저 들어간 회차를 돌려준다 — 응답을 잃고 다시 보내도 회차 · 확보가 둘이 되지 않는다.
 * - 증정품은 일반 판매(IN_STOCK) · 상품 · 옵션 모두 판매 중(ACTIVE) · 재고 등록(준비 완료)인 옵션이어야 한다. **공개 여부는 보지 않는다** —
 *   매장에 안 파는 증정품은 catalog 에 비공개 상품으로 먼저 등록해 둔다.
 * - 재고는 조건부 UPDATE 로 확보한다({@link StockLedger#reserve}) — 모자라면 409 INSUFFICIENT_STOCK 이고 회차를 만들지 않는다.
 *   확보한 재고는 당첨자의 증정(0원 주문)에 쓰인다.
 * - 응모 마감은 지금보다 뒤여야 한다. 시작은 지금보다 앞이어도 된다(바로 응모 중).
 */
@Service
public class AdminDrawService {

    private static final Logger log = LoggerFactory.getLogger(AdminDrawService.class);

    static final String IN_STOCK = "IN_STOCK";
    static final int MAX_KEY_LENGTH = 100;
    static final int MAX_ATTEMPTS = 2;
    static final String ACTIVE = "ACTIVE";

    private final CatalogReader catalog;
    private final StockLedger stock;
    private final DrawCampaignStore campaigns;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final Clock clock;

    public AdminDrawService(CatalogReader catalog, StockLedger stock, DrawCampaignStore campaigns,
                            PlatformTransactionManager transactionManager, Clock clock) {
        this.catalog = catalog;
        this.stock = stock;
        this.campaigns = campaigns;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
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
        CatalogOption option = catalog.find(List.of(command.optionId()), sessionToken).get(command.optionId());
        if (option == null || !option.productId().equals(command.productId())) {
            throw new BusinessException(OrderErrorCode.PRODUCT_NOT_FOUND);
        }
        requireGiftable(option);
        NewDrawCampaign draft = new NewDrawCampaign(key, option.productId(), option.optionId(), command.title(), option.productTitle(),
                option.optionTitle(), option.imageUrl(), command.entryFee(), command.winnerCount(), command.opensAt(), command.closesAt());
        for (int attempt = 1; ; attempt++) {
            try {
                return new Created(writeTransaction.execute(status -> {
                    stock.reserve(Map.of(option.optionId(), command.winnerCount()));
                    return campaigns.insert(draft);
                }), true);
            } catch (DrawKeyTakenException e) {
                // 같은 새 키가 동시에 왔다. 이 트랜잭션(확보 포함)은 롤백됐으므로 새 트랜잭션에서 먼저 들어간 회차를 읽는다
                return new Created(findByKey(key).orElseThrow(() -> new IllegalStateException("키 충돌인데 그 키의 회차가 없다: " + key, e)),
                        false);
            } catch (StockShortageException e) {
                throw new BusinessException(OrderErrorCode.INSUFFICIENT_STOCK, "당첨 인원만큼 재고가 없습니다.", Map.of("variantIds", e.optionIds()));
            } catch (PessimisticLockingFailureException e) {
                // 재고 원장의 계약 — 동시 삽입이 부르는 잠금(FK 부모 S 잠금)의 드문 교착은 호출하는 쪽이 한 번 더 한다(장바구니 주문과 같다)
                if (!MySqlLockFailures.isDeadlock(e) || attempt >= MAX_ATTEMPTS) {
                    log.warn("드로우 회차 만들기 잠금 실패 optionId={} attempt={}", option.optionId(), attempt, e);
                    throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                log.info("드로우 회차 만들기 교착, 다시 시도 {}/{} optionId={}", attempt, MAX_ATTEMPTS, option.optionId());
            }
        }
    }

    private Optional<DrawCampaign> findByKey(String key) {
        return readTransaction.execute(status -> campaigns.findByIdempotencyKey(key));
    }

    public OffsetPage<DrawCampaign> list(int page, int size) {
        return readTransaction.execute(status -> campaigns.findNewestFirst(page, size));
    }

    /** @throws BusinessException 404 DRAW_NOT_FOUND */
    public DrawCampaign get(UUID drawId) {
        return readTransaction.execute(status -> campaigns.findById(drawId))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.DRAW_NOT_FOUND));
    }

    public Instant now() {
        return clock.instant();
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
