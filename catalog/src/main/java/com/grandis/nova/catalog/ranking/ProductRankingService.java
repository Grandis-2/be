package com.grandis.nova.catalog.ranking;

import com.grandis.nova.catalog.cache.CatalogLocalCaches;
import com.grandis.nova.catalog.cache.LocalReadCache;
import com.grandis.nova.catalog.integration.PreorderCampaigns;
import com.grandis.nova.catalog.integration.PublicCampaigns;
import com.grandis.nova.catalog.listing.CampaignWindow;
import com.grandis.nova.catalog.listing.ProductListItem;
import com.grandis.nova.catalog.listing.ProductListingQueryRepository;
import com.grandis.nova.catalog.listing.SoldOutMarker;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.redis.cache.ReadCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 조회수 랭킹. 상세 화면이 뜰 때 프론트가 보내는 조회 신호를 세고({@link ProductViewCounter}), 최근 3일(KST 날짜) 조회수 순으로 앞 {@link #SIZE}개를
 * 목록 카드로 낸다. 랭킹에는 회원 목록과 같은 노출 규칙의 상품만 오른다 — 일반 판매와 접수 중인 사전예약(2026-10-18 결정).
 */
@Service
public class ProductRankingService {

    private static final Logger log = LoggerFactory.getLogger(ProductRankingService.class);

    static final int SIZE = 10;
    /** 후보를 이만큼씩 읽어 노출 규칙으로 거른다 — {@link #SIZE}개가 찰 때까지 다음 묶음을 읽는다. */
    static final int CANDIDATES = 50;
    /** 읽는 후보의 상한 — 조회수가 높은 상품이 한꺼번에 숨김 · 판매 중지 · 접수 마감이 돼도 그 아래에서 채우되, 끝없이 읽지 않는다. */
    static final int MAX_CANDIDATES = 500;

    private final ProductViewCounter counter;
    private final AnonymousQuota quota;
    private final ProductListingQueryRepository listings;
    private final SoldOutMarker soldOut;
    private final PreorderCampaigns campaigns;
    private final PublicCampaigns publicCampaigns;
    private final TransactionTemplate readTransaction;
    private final LocalReadCache<String, List<ProductListItem>> cache;
    private final Clock clock;

    public ProductRankingService(ProductViewCounter counter, AnonymousQuota quota, ProductListingQueryRepository listings, SoldOutMarker soldOut,
                                 PreorderCampaigns campaigns, PublicCampaigns publicCampaigns, PlatformTransactionManager transactionManager,
                                 CatalogLocalCaches local, Clock clock) {
        this.counter = counter;
        this.quota = quota;
        this.listings = listings;
        this.soldOut = soldOut;
        this.campaigns = campaigns;
        this.publicCampaigns = publicCampaigns;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        // 카드 본문과 색상칩을 두 문장으로 읽는다 — 회원 목록과 같이 한 스냅샷으로
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.cache = local.productRanking();   // 상품이 바뀌면 CatalogCacheInvalidation 이 비운다
        this.clock = clock;
    }

    /**
     * 상세 화면 조회 한 번. 상세처럼 공개 · 준비 끝난 상품만 받는다(아니면 404 — 없는 상품과 같은 응답). 같은 사람 · 같은 상품은 하루(KST) 한 번만 센다.
     * 익명이면 IP 별 익명 id 상한({@link AnonymousQuota})을 넘은 새 id 는 세지 않는다.
     * 사전예약의 준비(회차가 있는가)는 상세와 같이 {@link PublicCampaigns} 로 본다. DB · Redis · preorder 가 실패하면(장애 · 시간 초과) 경고만 남기고 넘어간다 — 조회 신호 하나를 놓치는 것이 상세 화면을 막는 것보다 낫다. 시간 초과된 쓰기는
     * 뒤늦게 반영될 수도 있다(시험에서 컨테이너를 멈췄다 풀 때 실측).
     *
     * @throws BusinessException NOT_FOUND — 없거나 공개가 아니거나 준비 전인 상품
     */
    public void recordView(UUID productId, ProductViewer viewer) {
        boolean exposed;
        try {
            ProductListingQueryRepository.Exposure exposure = listings.findExposure(productId).orElse(null);
            exposed = exposure != null && exposure.visible() && exposure.ready();
            if (exposed && exposure.saleMode() == SaleMode.PREORDER) {
                exposed = publicCampaigns.find(productId).isPresent();
            }
        } catch (DataAccessException | BusinessException e) {
            log.warn("조회 신호의 상품을 확인하지 못했다(DB · preorder) — 세지 않는다 productId={}", productId, e);
            return;
        }
        if (!exposed) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        try {
            Instant now = clock.instant();
            if (quota.admit(viewer, now)) {
                counter.record(productId, viewer, now);
            }
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
        // 사전예약은 회원 목록처럼 지금 접수 중인 것만 — preorder 의 접수 중 · 곧 열릴 회차로 가른다(답하지 않으면 마지막 값, 오래됐으면 사전예약 없이)
        Map<UUID, CampaignWindow> open = campaigns.open();
        Set<UUID> accepting = CampaignWindow.acceptingAt(open, now);
        // 묶음마다 날마다의 표를 다시 합치므로 그 사이 조회로 순위가 바뀌면 같은 상품이 두 묶음에 올 수 있다 — 상품 id 로 한 번만 담는다
        java.util.Map<UUID, ProductListItem> byId = new java.util.LinkedHashMap<>();
        for (int offset = 0; offset < MAX_CANDIDATES && byId.size() < SIZE; offset += CANDIDATES) {
            List<UUID> candidates;
            try {
                candidates = counter.top(now, offset, CANDIDATES);
            } catch (DataAccessException e) {
                log.warn("조회수 랭킹을 읽지 못했다(Redis)", e);
                throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
            }
            readTransaction.execute(status -> listings.findListedCards(candidates, accepting))
                    .forEach(card -> byId.putIfAbsent(card.productId(), card.withCampaign(open.get(card.productId()), now)));
            if (candidates.size() < CANDIDATES) {
                break;
            }
        }
        // 품절은 순위를 정한 뒤 order 재고로 채운다(트랜잭션 밖). 이 결과째 캐시되므로, order 가 답하지 않아 품절 표시를 뺀 순위도 캐시 수명만큼 남는다
        return soldOut.mark(byId.values().stream().limit(SIZE).toList());
    }
}
