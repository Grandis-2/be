package com.grandis.nova.catalog.ranking;

import com.grandis.nova.catalog.cache.LocalReadCache;
import com.grandis.nova.catalog.listing.ProductListItem;
import com.grandis.nova.catalog.listing.ProductListingQueryRepository;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.redis.cache.ReadCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 조회수 랭킹. 상세 화면이 뜰 때 프론트가 보내는 조회 신호를 세고({@link ProductViewCounter}), 최근 3일(KST 날짜) 조회수 순으로 앞 {@link #SIZE}개를
 * 목록 카드로 낸다. 랭킹에는 회원 목록과 같은 노출 규칙의 상품만 오른다 — 일반 판매와 접수 중인 사전예약(2026-10-18 결정).
 */
@Service
public class ProductRankingService {

    private static final Logger log = LoggerFactory.getLogger(ProductRankingService.class);

    static final int SIZE = 10;
    /** 노출 규칙으로 걸러도 {@link #SIZE}개가 차도록 앞에서 넉넉히 읽는다. 앞 50개 중 노출되는 상품이 10개보다 적으면 그만큼만 낸다. */
    static final int CANDIDATES = 50;
    /** 랭킹은 인스턴스 메모리에 이만큼 둔다 — 메인 화면마다 Redis 합치기 · DB 카드 조회를 하지 않게. */
    public static final Duration TTL = Duration.ofSeconds(30);
    /** 랭킹 캐시 무게 상한(JSON 바이트). 키 하나 · 카드 10개(약 15KB). */
    static final long MAX_BYTES = 1024 * 1024;

    private final ProductViewCounter counter;
    private final ProductListingQueryRepository listings;
    private final TransactionTemplate readTransaction;
    private final LocalReadCache<String, List<ProductListItem>> cache;
    private final Clock clock;

    public ProductRankingService(ProductViewCounter counter, ProductListingQueryRepository listings, PlatformTransactionManager transactionManager,
                                 JsonMapper json, Clock clock, @Value("${nova.read-cache.enabled:true}") boolean cacheEnabled) {
        this.counter = counter;
        this.listings = listings;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        // 카드 본문과 색상칩을 두 문장으로 읽는다 — 회원 목록과 같이 한 스냅샷으로
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.cache = new LocalReadCache<>("catalog.product-ranking", TTL, MAX_BYTES, value -> json.writeValueAsBytes(value).length, clock, cacheEnabled);
        this.clock = clock;
    }

    /**
     * 상세 화면 조회 한 번. 상세처럼 공개 · 준비 끝난 상품만 받는다(아니면 404 — 없는 상품과 같은 응답). 같은 사람 · 같은 상품은 하루(KST) 한 번만 센다.
     * Redis 가 실패하면(장애 · 시간 초과) 경고만 남기고 넘어간다 — 조회 신호 하나를 놓치는 것이 상세 화면을 막는 것보다 낫다. 시간 초과된 쓰기는
     * 뒤늦게 반영될 수도 있다(시험에서 컨테이너를 멈췄다 풀 때 실측).
     *
     * @throws BusinessException NOT_FOUND — 없거나 공개가 아니거나 준비 전인 상품
     */
    public void recordView(UUID productId, ProductViewer viewer) {
        boolean exposed = listings.findExposure(productId).map(e -> e.visible() && e.ready()).orElse(false);
        if (!exposed) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        try {
            counter.record(productId, viewer, clock.instant());
        } catch (DataAccessException e) {
            log.warn("조회수를 세지 못했다(Redis) productId={}", productId, e);
        }
    }

    /**
     * 최근 3일 조회수 순 앞 {@link #SIZE}개. 같은 조회수면 상품 id 역순이다.
     *
     * @throws BusinessException DEPENDENCY_UNAVAILABLE — 조회수를 읽을 수 없다(Redis 장애)
     */
    public ReadCache.Entry<List<ProductListItem>> ranking() {
        ReadCache.Entry<List<ProductListItem>> entry = cache.entry("views", this::load);
        Instant served = clock.instant();
        return new ReadCache.Entry<>(entry.value().stream().map(item -> item.at(served)).toList(), entry.loadedAt());
    }

    private List<ProductListItem> load() {
        Instant now = clock.instant();
        List<UUID> candidates;
        try {
            candidates = counter.top(now, CANDIDATES);
        } catch (DataAccessException e) {
            log.warn("조회수 랭킹을 읽지 못했다(Redis)", e);
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        List<ProductListItem> cards = readTransaction.execute(status -> listings.findListedCards(candidates, now));
        return cards.size() > SIZE ? List.copyOf(cards.subList(0, SIZE)) : cards;
    }
}
