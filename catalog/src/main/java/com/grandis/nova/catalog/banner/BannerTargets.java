package com.grandis.nova.catalog.banner;

import com.grandis.nova.catalog.listing.ProductListingQueryRepository;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.promotion.Promotion;
import com.grandis.nova.catalog.promotion.PromotionRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 배너의 연결 대상이 지금 고객에게 보이는가 — 자동 숨김(2026-10-18 결정). 일반 판매 상품은 회원 목록과 같은 노출 규칙(공개 · 판매 중 · 판매 준비)으로
 * 목록에 나오는 것, 홍보는 오늘(KST)이 홍보 기간 안인 것.
 */
@Component
class BannerTargets {

    static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final ProductListingQueryRepository listings;
    private final PromotionRepository promotions;
    private final Clock clock;

    BannerTargets(ProductListingQueryRepository listings, PromotionRepository promotions, Clock clock) {
        this.listings = listings;
        this.promotions = promotions;
        this.clock = clock;
    }

    /** @return 살아 있는 대상의 id(상품 · 홍보 id 를 섞어 담는다 — UUID 라 겹치지 않는다) */
    Set<UUID> live(Collection<Banner> banners) {
        Instant now = clock.instant();
        List<UUID> productIds = banners.stream().filter(b -> b.getTargetType() == BannerTargetType.PRODUCT).map(Banner::getTargetId).distinct().toList();
        Set<UUID> live = new HashSet<>();
        // 대상은 일반 상품만 — 사전예약의 접수 중을 넘기지 않으므로(빈 목록) preorder 를 부르지 않는다
        listings.findListedCards(productIds, List.of()).stream().filter(card -> card.saleMode() == SaleMode.IN_STOCK)
                .forEach(card -> live.add(card.productId()));
        if (banners.stream().anyMatch(b -> b.getTargetType() == BannerTargetType.PROMOTION)) {
            promotions.findShownOn(LocalDate.ofInstant(now, KOREA)).stream().map(Promotion::getId).forEach(live::add);
        }
        return live;
    }
}
