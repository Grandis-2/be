package com.grandis.nova.catalog.promotion;

import com.grandis.nova.catalog.cache.CatalogLocalCaches;
import com.grandis.nova.catalog.cache.CatalogReadCache;
import com.grandis.nova.catalog.cache.LocalReadCache;
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
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 고객 사전예약 페이지의 홍보 목록 · 상세(로그인 없이). 오늘(한국 날짜)이 홍보 기간 안인 홍보만 보인다 — 밖이면 상세도 404.
 * 홍보 내용(이름 · 사진 · 기간 · 묶인 상품 id)은 읽기 캐시에 {@link #BODY_TTL} 두고 고치면 세대로 비운다. 상품 카드(가격 · 판매 준비 · 색상칩)는 상품 목록과
 * 같은 카드라 같은 TTL({@link CatalogReadCache#PRODUCT_TTL})로 인스턴스 메모리에 둔다({@link CatalogLocalCaches#promotionCards}). 키에 상품 세대 번호가 있어
 * 상품이 바뀌면(관리자 수정 · 판매 준비 이벤트) 모든 인스턴스가 다음 조회부터 새로 읽는다 — 숨김 · 판매 중지 · 삭제가 바로 빠진다. 세대를 올리지 않는 변경
 * (DB 를 직접 고침)은 TTL 뒤에 보인다. 세대를 못 읽으면(Redis 실패) 이 인스턴스가 마지막으로 본 세대의 카드를 TTL 안에서 그대로 쓴다 — 그동안 다른 인스턴스의
 * 숨김 · 삭제는 최대 TTL 늦다(사용자 결정 2026-10-22, 그때는 세대 올리기도 미뤄진다). 그런 카드가 없을 때만 넣지 않고 DB 로 읽는다 — 같은 키의 동시 조회는
 * 한 번으로 합치고, 그 조회가 기다림 상한(5초)을 넘기면 기다린 쪽은 503 이다(Redis 판 우회의 합치기와 같다). 회차 · 품절은 카드 캐시 밖이라 요청마다 본다 —
 * 사전예약 카드의 회차(준비 · 시각)는 상세와 같이 {@link PublicCampaigns} 로 보고 접수 단계는 지금 시각으로 계산한다 —
 * preorder 가 답하지 않으면 페이지는 그대로 내고 사전예약 카드의 시각 · 접수 단계만 비운다(2026-10-19 결정).
 */
@Service
public class PromotionQueryService {

    /** 홍보를 고치면 올리는 세대. */
    public static final String PROMOTIONS = "promotions";
    /** 홍보 내용 — 고치면 바로 비우므로 이 값은 놓친 비우기의 상한이다. */
    public static final Duration BODY_TTL = Duration.ofMinutes(10);

    /** 캐시에 두는 홍보 내용. */
    public record Body(UUID promotionId, String name, String thumbnailUrl, List<String> detailImageUrls, LocalDate startDate, LocalDate endDate,
                List<UUID> productIds) {
    }

    private static final Logger log = LoggerFactory.getLogger(PromotionQueryService.class);

    private final PromotionRepository promotions;
    private final PromotionProducts links;
    private final ProductListingQueryRepository crossReads;
    private final SoldOutMarker soldOut;
    private final PublicCampaigns campaigns;
    private final ReadCache cache;
    private final LocalReadCache<CatalogLocalCaches.CardsKey, List<ProductListItem>> cardCache;
    private final Clock clock;
    private final ReadCache.Spec<Body> bodySpec;
    private final ReadCache.Spec<List<Body>> shownSpec;

    public PromotionQueryService(PromotionRepository promotions, PromotionProducts links, ProductListingQueryRepository crossReads,
                                 SoldOutMarker soldOut, PublicCampaigns campaigns, ReadCache cache, CatalogLocalCaches local, Clock clock) {
        this.promotions = promotions;
        this.links = links;
        this.crossReads = crossReads;
        this.soldOut = soldOut;
        this.campaigns = campaigns;
        this.cache = cache;
        this.cardCache = local.promotionCards();   // 상품이 바뀌면 키의 상품 세대 번호가 바뀐다(모든 인스턴스)
        this.clock = clock;
        // Redis 를 못 읽어도 이 인스턴스의 사본을 TTL 안에서 쓴다(ReadCache.OutageCopies) — 홍보 상세가 몰릴 때 Redis 가 잠깐 늦어도 DB 로 줄 서지 않게.
        // 장애 중 다른 인스턴스의 홍보 수정은 최대 이만큼 늦게 보인다(2026-10-22 결정). 숨긴 상품은 카드(상품 세대)에서 빠진다.
        // 평소에도 같은 세대의 사본이 미리 갱신 시점 전이면 그것으로 답한다(세대는 Redis 에서 읽는다)
        this.bodySpec = cache.spec("promotion-body", Body.class, BODY_TTL, BODY_TTL);
        this.shownSpec = cache.spec("promotion-shown", new TypeReference<List<Body>>() { }, BODY_TTL);
    }

    /**
     * 응답 값과 그 값을 더 들고 있어도 되는 시간 — 본문(TTL {@link #BODY_TTL})과 카드(TTL {@link CatalogReadCache#PRODUCT_TTL})의 남은 시간 중 짧은 쪽.
     * 앞단 HTTP 캐시의 max-age 상한이다 — 메모리에서 묵은 카드에 max-age 를 통째로 더 주면 합쳐서 TTL 을 넘겨 옛 값이 보인다.
     */
    public record Served<T>(T value, Duration remaining) {
    }

    /** 오늘 보이는 홍보 — 시작이 늦은 것부터. 키에 오늘 날짜가 들어가 날이 바뀌면 새로 읽는다. */
    public Served<List<PromotionViews.Summary>> shown() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), PromotionService.KOREA);
        ReadCache.Generations generations = cache.generations(PROMOTIONS, CatalogReadCache.PRODUCTS, PublicCampaigns.CAMPAIGNS);
        ReadCache.Entry<List<Body>> bodies = cache.entry(shownSpec, generations, PROMOTIONS, today.toString(), () -> loadShown(today));
        List<UUID> allIds = bodies.value().stream().flatMap(body -> body.productIds().stream()).toList();
        ReadCache.Entry<List<ProductListItem>> found = cards(allIds, false, generations);
        Map<UUID, ProductListItem> cards = new HashMap<>();
        found.value().forEach(card -> cards.put(card.productId(), card));
        List<PromotionViews.Summary> summaries = bodies.value().stream().map(body -> {
            List<ProductListItem> mine = body.productIds().stream().map(cards::get).filter(Objects::nonNull).toList();
            return new PromotionViews.Summary(body.promotionId(), body.name(), body.thumbnailUrl(), body.startDate(), body.endDate(),
                    earliestOpen(mine), latestClose(mine));
        }).toList();
        return served(summaries, bodies, found);
    }

    /** @throws BusinessException 404 — 없거나 오늘이 홍보 기간 밖 */
    public Served<PromotionViews.Detail> detail(UUID promotionId) {
        // 홍보 · 상품 · 회차 세대를 한 번에 읽는다(MGET) — 차례로 GET 하면 Redis 왕복이 셋이다
        ReadCache.Generations generations = cache.generations(PROMOTIONS, CatalogReadCache.PRODUCTS, PublicCampaigns.CAMPAIGNS);
        ReadCache.Entry<Body> body = cache.entry(bodySpec, generations, PROMOTIONS, promotionId.toString(), () -> loadBody(promotionId));
        LocalDate today = LocalDate.ofInstant(clock.instant(), PromotionService.KOREA);
        if (today.isBefore(body.value().startDate()) || today.isAfter(body.value().endDate())) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        ReadCache.Entry<List<ProductListItem>> found = cards(body.value().productIds(), true, generations);
        List<ProductListItem> cards = soldOut.mark(found.value());
        Body b = body.value();
        return served(new PromotionViews.Detail(b.promotionId(), b.name(), b.thumbnailUrl(), b.detailImageUrls(), b.startDate(),
                b.endDate(), earliestOpen(cards), latestClose(cards), cards), body, found);
    }

    private <T> Served<T> served(T value, ReadCache.Entry<?> body, ReadCache.Entry<?> cards) {
        Instant now = clock.instant();
        Duration bodyLeft = body.remaining(BODY_TTL, now);
        Duration cardsLeft = cards.remaining(CatalogReadCache.PRODUCT_TTL, now);
        return new Served<>(value, bodyLeft.compareTo(cardsLeft) < 0 ? bodyLeft : cardsLeft);
    }

    /**
     * 홍보 카드에 사전예약 회차를 채운다 — 회차가 없는 사전예약 상품(판매 준비 전)은 뺀다. preorder 가 답하지 않으면 준비를 가르지 못하므로 빼지 않고,
     * 시각 · 접수 단계를 비워 낸다.
     */
    /** @param generations 이 요청이 먼저 읽은 세대(상품 · 회차) */
    private ReadCache.Entry<List<ProductListItem>> cards(List<UUID> productIds, boolean withColors, ReadCache.Generations generations) {
        if (productIds.isEmpty()) {
            return new ReadCache.Entry<>(List.of(), clock.instant());   // 오늘 보이는 홍보가 없다
        }
        // 빈 결과(묶은 상품이 모두 숨김 · 준비 전)는 넣지 않는다 — 준비되면 TTL 을 기다리지 않고 나오게. 읽은 시각은 응답의 max-age 에 쓴다
        OptionalLong products = generations.number(CatalogReadCache.PRODUCTS);
        ReadCache.Entry<List<ProductListItem>> entry;
        if (products.isPresent()) {
            entry = cardCache.entry(new CatalogLocalCaches.CardsKey(productIds, withColors, products.getAsLong()),
                    () -> crossReads.findCards(productIds, withColors), found -> !found.isEmpty());
        } else {
            // 세대를 못 읽으면(Redis 실패) 마지막으로 본 세대의 카드를 TTL 안에서 그대로 쓴다(사용자 결정 2026-10-22 — 장애 중 숨김은 최대 TTL 늦음).
            // 없을 때만 DB 로 읽되 넣지 않고, 같은 자리를 거쳐 동시 요청이 한 번을 나눠 받는다
            OptionalLong seen = cache.lastSeenGeneration(CatalogReadCache.PRODUCTS);
            ReadCache.Entry<List<ProductListItem>> held = seen.isPresent()
                    ? cardCache.peek(new CatalogLocalCaches.CardsKey(productIds, withColors, seen.getAsLong())) : null;
            entry = held != null ? held : cardCache.entry(new CatalogLocalCaches.CardsKey(productIds, withColors, CatalogLocalCaches.CardsKey.UNREADABLE),
                    () -> crossReads.findCards(productIds, withColors), found -> false);
        }
        List<ProductListItem> cards = entry.value();
        List<UUID> preorderIds = cards.stream().filter(card -> card.saleMode() == SaleMode.PREORDER).map(ProductListItem::productId).toList();
        Instant now = clock.instant();
        Map<UUID, CampaignWindow> windows;
        try {
            windows = campaigns.find(preorderIds, generations);
        } catch (BusinessException e) {
            log.warn("홍보 카드의 회차를 preorder 에 묻지 못했다 — 사전예약 카드의 시각을 비우고 보낸다 products={}", preorderIds.size(), e);
            return entry.map(found -> found.stream().map(card -> card.withCampaign(null, now)).toList());
        }
        return entry.map(found -> found.stream().filter(card -> card.saleMode() != SaleMode.PREORDER || windows.containsKey(card.productId()))
                .map(card -> card.withCampaign(windows.get(card.productId()), now)).toList());
    }

    // 홍보 · 묶음 두 문장 — 트랜잭션 없이 읽는다(늦게 묶인 상품이 이번 응답에 빠져도 다음 세대에서 보인다)
    private List<Body> loadShown(LocalDate today) {
        List<Promotion> found = promotions.findShownOn(today);
        Map<UUID, List<UUID>> linked = links.findByPromotions(found.stream().map(Promotion::getId).toList());
        return found.stream().map(p -> body(p, linked.get(p.getId()))).toList();
    }

    private Body loadBody(UUID promotionId) {
        Promotion promotion = promotions.findById(promotionId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        return body(promotion, links.findByPromotions(List.of(promotionId)).get(promotionId));
    }

    private static Body body(Promotion promotion, List<UUID> productIds) {
        return new Body(promotion.getId(), promotion.getName(), promotion.getThumbnailUrl(), promotion.getDetailImageUrls(),
                promotion.getStartDate(), promotion.getEndDate(), List.copyOf(productIds));
    }

    private static Instant earliestOpen(List<ProductListItem> cards) {
        return cards.stream().filter(card -> card.saleMode() == SaleMode.PREORDER).map(ProductListItem::opensAt).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
    }

    private static Instant latestClose(List<ProductListItem> cards) {
        return cards.stream().filter(card -> card.saleMode() == SaleMode.PREORDER).map(ProductListItem::closesAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }
}
