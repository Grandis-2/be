package com.grandis.nova.catalog.banner;

import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.catalog.image.ImageAttachments;
import com.grandis.nova.catalog.image.ImagePurpose;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.ProductRepository;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.promotion.PromotionRepository;
import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.redis.cache.ReadCache;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 관리자 배너 — 만들기 · 고치기 · 지우기 · 순서 바꾸기 · 목록(2026-10-18 결정: 관리자가 순서와 노출을 정하고, 게시 기간은 없다).
 * <ul>
 *   <li>연결 대상은 일반 판매 상품(삭제하지 않은) 또는 있는 홍보만(400 target). 저장한 뒤 대상이 숨김 · 판매 중지 · 기간 끝이 되면 지우지 않고 고객
 *       조회에서만 빠진다({@link BannerTargets}).</li>
 *   <li>아이콘은 lucide 이름 허용 목록만(400 icon, {@link BannerIcons}).</li>
 *   <li>사진은 업로드 API 로 올린 배너 사진(banners/)만 — 붙이기 · 떼기는 {@link ImageAttachments}.</li>
 *   <li>새 배너는 맨 뒤에 붙는다. 순서 바꾸기는 모든 배너 id 를 원하는 순서로 한 번에 보낸다(빠지거나 남는 id 가 있으면 400).</li>
 *   <li>바꾸면 커밋 뒤 고객 조회 캐시의 배너 세대를 올린다({@link BannerQueryService#BANNERS}).</li>
 *   <li>잠금은 배너 행이 먼저, 사진 행이 나중이다(만들기 · 순서 바꾸기는 전부, 고치기 · 지우기는 그 배너 하나). 잠금 대기 초과 · 교착은 409 retryable.</li>
 * </ul>
 */
@Service
public class BannerService {

    private final BannerRepository banners;
    private final ProductRepository products;
    private final PromotionRepository promotions;
    private final BannerTargets targets;
    private final ImageAttachments images;
    private final ReadCache cache;
    private final TransactionTemplate tx;

    public BannerService(BannerRepository banners, ProductRepository products, PromotionRepository promotions, BannerTargets targets,
                         ImageAttachments images, ReadCache cache, PlatformTransactionManager transactionManager) {
        this.banners = banners;
        this.products = products;
        this.promotions = promotions;
        this.targets = targets;
        this.images = images;
        this.cache = cache;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** @throws BusinessException 400 VALIDATION_FAILED — 아이콘 · 대상 · 사진 */
    public BannerViews.Admin create(BannerRequest request) {
        validate(request);
        UUID id = locked(() -> tx.execute(status -> {
            List<Banner> all = banners.lockAllInOrder();
            int position = all.isEmpty() ? 0 : all.getLast().getPosition() + 1;
            Banner banner = banners.saveAndFlush(Banner.create(request, position));
            images.sync(ImagePurpose.BANNER, List.of(), List.of(request.imageUrl()), url -> "imageUrl");
            cache.bumpAfterCommit(BannerQueryService.BANNERS);
            return banner.getId();
        }));
        return view(id);
    }

    /** @throws BusinessException 404 · 400 VALIDATION_FAILED */
    public BannerViews.Admin update(UUID bannerId, BannerRequest request) {
        validate(request);
        locked(() -> tx.execute(status -> {
            Banner banner = banners.findForUpdate(bannerId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
            String before = banner.getImageUrl();
            banner.change(request);
            banners.flush();
            images.sync(ImagePurpose.BANNER, List.of(before), List.of(request.imageUrl()), url -> "imageUrl");
            cache.bumpAfterCommit(BannerQueryService.BANNERS);
            return bannerId;
        }));
        return view(bannerId);
    }

    /** 지운다(되살리기 없음). 남은 배너의 순서는 그대로다(빈 번호는 순서에 영향이 없다). @throws BusinessException 404 */
    public void delete(UUID bannerId) {
        locked(() -> tx.execute(status -> {
            Banner banner = banners.findForUpdate(bannerId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
            images.sync(ImagePurpose.BANNER, List.of(banner.getImageUrl()), List.of(), url -> "imageUrl");
            banners.delete(banner);
            cache.bumpAfterCommit(BannerQueryService.BANNERS);
            return bannerId;
        }));
    }

    /**
     * 순서를 통째로 바꾼다 — 모든 배너 id 를 보이는 순서대로. 지금 있는 배너와 정확히 같은 집합이어야 한다(400 bannerIds — 겹친 만들기 · 지우기가
     * 사이에 끼었으면 새로 읽고 다시 보낸다).
     */
    public List<BannerViews.Admin> reorder(List<UUID> bannerIds) {
        locked(() -> tx.execute(status -> {
            List<Banner> all = banners.lockAllInOrder();
            if (bannerIds.size() != all.size() || !new HashSet<>(bannerIds).equals(all.stream().map(Banner::getId).collect(Collectors.toSet()))) {
                throw ValidationFailures.of("bannerIds", "지금 있는 배너 id 를 모두 한 번씩 보내야 합니다.");
            }
            Map<UUID, Banner> byId = all.stream().collect(Collectors.toMap(Banner::getId, Function.identity()));
            for (int i = 0; i < bannerIds.size(); i++) {
                byId.get(bannerIds.get(i)).moveTo(i);
            }
            cache.bumpAfterCommit(BannerQueryService.BANNERS);
            return all.size();
        }));
        return list();
    }

    /** 모든 배너 — 보이는 순서. 대상이 지금 살아 있는지 같이 준다. */
    public List<BannerViews.Admin> list() {
        List<Banner> all = banners.findAllByOrderByPositionAscIdAsc();
        var live = targets.live(all);
        return all.stream().map(b -> BannerViews.Admin.of(b, live.contains(b.getTargetId()))).toList();
    }

    private BannerViews.Admin view(UUID bannerId) {
        Banner banner = banners.findById(bannerId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        return BannerViews.Admin.of(banner, targets.live(List.of(banner)).contains(banner.getTargetId()));
    }

    private static <T> T locked(Supplier<T> work) {
        try {
            return work.get();
        } catch (PessimisticLockingFailureException e) {
            throw new BusinessException(CatalogErrorCode.STATE_CONFLICT, "다른 작업과 겹쳐 처리하지 못했습니다. 다시 시도해 주세요.",
                    Map.of("retryable", true));
        }
    }

    private void validate(BannerRequest request) {
        if (!BannerIcons.ALLOWED.contains(request.icon())) {
            throw ValidationFailures.of("icon", "쓸 수 있는 아이콘이 아닙니다.");
        }
        UUID targetId = request.target().id();
        switch (request.target().type()) {
            case PRODUCT -> {
                Product product = products.findById(targetId).orElse(null);
                if (product == null || product.isDeleted() || product.getSaleMode() != SaleMode.IN_STOCK) {
                    throw ValidationFailures.of("target.id", "일반 판매 상품만 연결할 수 있습니다.");
                }
            }
            case PROMOTION -> {
                if (!promotions.existsById(targetId)) {
                    throw ValidationFailures.of("target.id", "없는 홍보입니다.");
                }
            }
        }
    }
}
