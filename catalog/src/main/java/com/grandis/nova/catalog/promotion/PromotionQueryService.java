package com.grandis.nova.catalog.promotion;

import com.grandis.nova.catalog.listing.ProductListItem;
import com.grandis.nova.catalog.listing.ProductListingQueryRepository;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.redis.cache.ReadCache;
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
import java.util.UUID;

/**
 * 고객 사전예약 페이지의 홍보 목록 · 상세(로그인 없이). 오늘(한국 날짜)이 홍보 기간 안인 홍보만 보인다 — 밖이면 상세도 404.
 * 홍보 내용(이름 · 사진 · 기간 · 묶인 상품 id)은 읽기 캐시에 {@link #BODY_TTL} 두고 고치면 세대로 비운다. 상품 카드(가격 · 회차 · 재고)는 다른 서비스가
 * 바꾸는 값이 섞여 요청마다 읽는다 — 상품 상세와 같은 나눔이다.
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

    private final PromotionRepository promotions;
    private final PromotionProducts links;
    private final ProductListingQueryRepository crossReads;
    private final ReadCache cache;
    private final Clock clock;
    private final ReadCache.Spec<Body> bodySpec;
    private final ReadCache.Spec<List<Body>> shownSpec;

    public PromotionQueryService(PromotionRepository promotions, PromotionProducts links, ProductListingQueryRepository crossReads,
                                 ReadCache cache, Clock clock) {
        this.promotions = promotions;
        this.links = links;
        this.crossReads = crossReads;
        this.cache = cache;
        this.clock = clock;
        this.bodySpec = cache.spec("promotion-body", Body.class, BODY_TTL);
        this.shownSpec = cache.spec("promotion-shown", new TypeReference<List<Body>>() { }, BODY_TTL);
    }

    /** 오늘 보이는 홍보 — 시작이 늦은 것부터. 키에 오늘 날짜가 들어가 날이 바뀌면 새로 읽는다. */
    public ReadCache.Entry<List<PromotionViews.Summary>> shown() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), PromotionService.KOREA);
        ReadCache.Entry<List<Body>> bodies = cache.entry(shownSpec, PROMOTIONS, today.toString(), () -> loadShown(today));
        List<UUID> allIds = bodies.value().stream().flatMap(body -> body.productIds().stream()).toList();
        Map<UUID, ProductListItem> cards = new HashMap<>();
        crossReads.findCards(allIds, clock.instant()).forEach(card -> cards.put(card.productId(), card));
        return bodies.map(list -> list.stream().map(body -> {
            List<ProductListItem> mine = body.productIds().stream().map(cards::get).filter(Objects::nonNull).toList();
            return new PromotionViews.Summary(body.promotionId(), body.name(), body.thumbnailUrl(), body.startDate(), body.endDate(),
                    earliestOpen(mine), latestClose(mine));
        }).toList());
    }

    /** @throws BusinessException 404 — 없거나 오늘이 홍보 기간 밖 */
    public ReadCache.Entry<PromotionViews.Detail> detail(UUID promotionId) {
        ReadCache.Entry<Body> body = cache.entry(bodySpec, PROMOTIONS, promotionId.toString(), () -> loadBody(promotionId));
        LocalDate today = LocalDate.ofInstant(clock.instant(), PromotionService.KOREA);
        if (today.isBefore(body.value().startDate()) || today.isAfter(body.value().endDate())) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        List<ProductListItem> cards = crossReads.findCards(body.value().productIds(), clock.instant());
        return body.map(b -> new PromotionViews.Detail(b.promotionId(), b.name(), b.thumbnailUrl(), b.detailImageUrls(), b.startDate(),
                b.endDate(), earliestOpen(cards), latestClose(cards), cards));
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
