package com.grandis.nova.catalog.promotion;

import com.grandis.nova.catalog.image.ImageAttachments;
import com.grandis.nova.catalog.image.ImagePurpose;
import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.catalog.listing.ProductListingQueryRepository;
import com.grandis.nova.catalog.option.OptionText;
import com.grandis.nova.catalog.option.ProductOptions;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.ProductRepository;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.web.ConstraintViolations;
import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.redis.cache.ReadCache;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 관리자 홍보 — 만들기 · 고치기 · 목록 · 상세 · 묶을 수 있는 상품.
 *
 * <ul>
 *   <li>새로 묶는 상품은 회차가 취소되지 않은 사전예약 상품만(400). 이미 묶인 상품은 일반으로 바꿨어도 고칠 때 그대로 둘 수 있다(2026-10-14 결정).</li>
 *   <li>한 상품은 홍보 하나에만 — 다른 홍보에 묶인 상품이면 409(details.productIds = 실제로 겹친 상품). 겹친 두 요청은 DB 유일 키(uq_promotion_product)가
 *       하나만 남긴다. 잠금 대기 초과 · 교착은 409 retryable.</li>
 *   <li>새로 쓰는 사진은 업로드 API 로 올린 홍보 사진(promotions/)만(400) — 붙이기 · 떼기는 {@link ImageAttachments}. 썸네일과 상세의 같은 주소는 받고,
 *       상세 목록 안의 중복은 400.</li>
 *   <li>만들기는 Idempotency-Key 로 한 번 — 같은 키로 다시 오면 처음 홍보를 돌려준다(내용은 대조하지 않는다, 상품 등록과 같다).</li>
 *   <li>고치면 커밋 뒤 고객 조회 캐시의 홍보 세대를 올린다({@link PromotionQueryService#PROMOTIONS}).</li>
 * </ul>
 */
@Service
public class PromotionService {

    /** 같은 Idempotency-Key 유일 키 이름. */
    static final String IDEMPOTENCY_KEY = "uq_promotion_idempotency";
    /** 홍보 기간 · 진행 단계를 가르는 날짜의 시간대. */
    static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final PromotionRepository promotions;
    private final PromotionProducts links;
    private final ProductRepository products;
    private final ProductListingQueryRepository crossReads;
    private final ImageAttachments images;
    private final ReadCache cache;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PromotionService(PromotionRepository promotions, PromotionProducts links, ProductRepository products,
                            ProductListingQueryRepository crossReads, ImageAttachments images, ReadCache cache,
                            PlatformTransactionManager transactionManager, Clock clock) {
        this.promotions = promotions;
        this.links = links;
        this.products = products;
        this.crossReads = crossReads;
        this.images = images;
        this.cache = cache;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 만든 결과. replayed 면 같은 키의 처음 홍보다. */
    public record Created(AdminPromotionView promotion, boolean replayed) {
    }

    public Created create(String idempotencyKey, PromotionRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw ValidationFailures.of("Idempotency-Key", "1~100자여야 합니다.");
        }
        var existing = promotions.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new Created(view(existing.get().getId()), true);
        }
        try {
            UUID id = tx.execute(status -> {
                validate(request, Set.of());
                Promotion promotion = promotions.saveAndFlush(Promotion.create(idempotencyKey, request.name(), request.startDate(), request.endDate(),
                        request.thumbnailUrl(), request.detailImageUrls()));
                // 멱등 키 INSERT(saveAndFlush) 뒤에 붙인다 — 같은 키 동시 재전송은 위 INSERT 에서 기다렸다 중복으로 끝나 처음 홍보를 받는다
                images.sync(ImagePurpose.PROMOTION, List.of(), imageUrls(request.thumbnailUrl(), request.detailImageUrls()), url -> imageField(request, url));
                links.replace(promotion.getId(), request.productIds());
                cache.bumpAfterCommit(PromotionQueryService.PROMOTIONS);
                return promotion.getId();
            });
            return new Created(view(id), false);
        } catch (DataIntegrityViolationException e) {
            // 겹친 요청이 먼저 커밋했다 — 같은 키면 그 홍보를, 같은 상품이면 실제로 겹친 상품을 알린다. 다른 제약 위반은 그대로 던진다
            if (ConstraintViolations.mentionsKey(e, IDEMPOTENCY_KEY)) {
                return new Created(view(promotions.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e).getId()), true);
            }
            if (ConstraintViolations.mentionsKey(e, PromotionProducts.UNIQUE_PRODUCT)) {
                throw linkedElsewhere(ownedByOthers(request.productIds(), null));
            }
            throw e;
        } catch (PessimisticLockingFailureException e) {
            throw retryable();
        }
    }

    public AdminPromotionView replace(UUID promotionId, PromotionRequest request) {
        try {
            tx.executeWithoutResult(status -> {
                Promotion promotion = promotions.findForUpdate(promotionId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
                Set<UUID> current = new HashSet<>(links.findByPromotions(List.of(promotionId)).get(promotionId));
                validate(request, current);
                images.sync(ImagePurpose.PROMOTION, imageUrls(promotion.getThumbnailUrl(), promotion.getDetailImageUrls()),
                        imageUrls(request.thumbnailUrl(), request.detailImageUrls()), url -> imageField(request, url));
                promotion.change(request.name(), request.startDate(), request.endDate(), request.thumbnailUrl(), request.detailImageUrls());
                promotions.flush();
                links.replace(promotionId, request.productIds());
                cache.bumpAfterCommit(PromotionQueryService.PROMOTIONS);
            });
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.mentionsKey(e, PromotionProducts.UNIQUE_PRODUCT)) {
                throw linkedElsewhere(ownedByOthers(request.productIds(), promotionId));
            }
            throw e;
        } catch (PessimisticLockingFailureException e) {
            throw retryable();
        }
        return view(promotionId);
    }

    public AdminPromotionView get(UUID promotionId) {
        return view(promotionId);
    }

    public OffsetPage<AdminPromotionView> list(int page, int size) {
        Page<Promotion> found = promotions.findNewestFirst(PageRequest.of(page, size));
        Map<UUID, List<UUID>> linked = links.findByPromotions(found.getContent().stream().map(Promotion::getId).toList());
        Map<UUID, Product> productsById = productsOf(linked.values().stream().flatMap(List::stream).toList());
        LocalDate today = today();
        return OffsetPage.of(found.getContent().stream().map(p -> view(p, linked.get(p.getId()), productsById, today)).toList(),
                page, size, found.getTotalElements());
    }

    /** 묶을 수 있는 상품. promotionId 를 주면 그 홍보에 이미 묶인 상품도 싣는다(수정 화면). */
    public OffsetPage<LinkableProduct> linkable(UUID promotionId, int page, int size) {
        OffsetPage<ProductListingQueryRepository.LinkableRow> rows = crossReads.findLinkable(promotionId, page, size);
        Map<UUID, Product> productsById = productsOf(rows.items().stream().map(ProductListingQueryRepository.LinkableRow::productId).toList());
        return rows.map(row -> {
            ProductOptions options = productsById.get(row.productId()).getOptions();
            return new LinkableProduct(row.productId(), row.title(), row.saleMode(), row.opensAt(), values(options, OptionText.COLOR),
                    values(options, OptionText.STORAGE));
        });
    }

    /**
     * 본문 판정. current 는 이 홍보에 이미 묶인 상품(만들기는 빈 집합) — 그 상품은 판매 방식 검사를 건너뛴다(일반으로 바꾼 뒤에도 남겨 둘 수 있게).
     */
    private void validate(PromotionRequest request, Set<UUID> current) {
        if (request.endDate().isBefore(request.startDate())) {
            throw ValidationFailures.of("endDate", "끝 날이 시작 날보다 앞입니다.");
        }
        // 썸네일과 상세 사이의 같은 주소는 받는다(같은 홍보 안의 재사용 — 붙이기는 합친 집합으로 한다). 상세 목록 안의 중복은 400
        Set<String> urls = new HashSet<>();
        for (int i = 0; i < request.detailImageUrls().size(); i++) {
            String url = request.detailImageUrls().get(i);
            if (!urls.add(url)) {
                throw ValidationFailures.of("detailImageUrls[" + i + "]", "같은 사진 주소가 두 번 왔습니다.");
            }
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (int i = 0; i < request.productIds().size(); i++) {
            if (!ids.add(request.productIds().get(i))) {
                throw ValidationFailures.of("productIds[" + i + "]", "같은 상품이 두 번 왔습니다.");
            }
        }
        Map<UUID, Product> found = productsOf(request.productIds());
        for (int i = 0; i < request.productIds().size(); i++) {
            UUID productId = request.productIds().get(i);
            Product product = found.get(productId);
            if (product == null) {
                throw ValidationFailures.of("productIds[" + i + "]", "없는 상품입니다.");
            }
            if (!current.contains(productId) && (product.getSaleMode() != SaleMode.PREORDER || product.getCampaignCanceledAt() != null)) {
                throw ValidationFailures.of("productIds[" + i + "]", "회차가 취소되지 않은 사전예약 상품만 새로 묶을 수 있습니다.");
            }
        }
        List<UUID> elsewhere = links.findOwners(request.productIds()).entrySet().stream()
                .filter(owner -> !current.contains(owner.getKey()))
                .map(Map.Entry::getKey)
                .toList();
        if (!elsewhere.isEmpty()) {
            throw linkedElsewhere(elsewhere);
        }
    }

    /** 홍보의 사진 주소 — 썸네일과 상세를 합친 집합(같은 홍보 안의 같은 주소는 한 사진이다). */
    private static List<String> imageUrls(String thumbnailUrl, List<String> detailImageUrls) {
        List<String> urls = new ArrayList<>();
        urls.add(thumbnailUrl);
        urls.addAll(detailImageUrls);
        return urls;
    }

    private static String imageField(PromotionRequest request, String url) {
        if (url.equals(request.thumbnailUrl())) {
            return "thumbnailUrl";
        }
        return "detailImageUrls[" + request.detailImageUrls().indexOf(url) + "]";
    }

    /** 겹친 요청이 커밋한 뒤 — 요청한 상품 중 지금 다른 홍보(self 아닌)에 묶인 것. */
    private List<UUID> ownedByOthers(List<UUID> productIds, UUID self) {
        return links.findOwners(productIds).entrySet().stream()
                .filter(owner -> !owner.getValue().equals(self))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 잠금 대기 초과 · 교착 — 다른 작업과 겹쳤다. 상품 수정과 같은 409 다시 시도(AdminProductController#serialized). */
    private static BusinessException retryable() {
        return new BusinessException(CatalogErrorCode.STATE_CONFLICT, "다른 작업과 겹쳐 처리하지 못했습니다. 다시 시도해 주세요.",
                Map.of("retryable", true));
    }

    private static BusinessException linkedElsewhere(List<UUID> productIds) {
        return new BusinessException(CatalogErrorCode.STATE_CONFLICT, "다른 홍보에 묶인 상품이 있습니다. 한 상품은 홍보 하나에만 묶습니다.",
                Map.of("productIds", productIds.stream().map(UUID::toString).toList()));
    }

    private AdminPromotionView view(UUID promotionId) {
        Promotion promotion = promotions.findById(promotionId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        List<UUID> linked = links.findByPromotions(List.of(promotionId)).get(promotionId);
        return view(promotion, linked, productsOf(linked), today());
    }

    private static AdminPromotionView view(Promotion promotion, List<UUID> linked, Map<UUID, Product> productsById, LocalDate today) {
        return new AdminPromotionView(promotion.getId(), promotion.getName(), promotion.statusOn(today), promotion.getStartDate(),
                promotion.getEndDate(), promotion.getThumbnailUrl(), promotion.getDetailImageUrls(),
                linked.stream().map(productsById::get)
                        .map(product -> new AdminPromotionView.LinkedProduct(product.getId(), product.getTitle(), product.getSaleMode()))
                        .toList(),
                promotion.getCreatedAt(), promotion.getUpdatedAt());
    }

    private Map<UUID, Product> productsOf(List<UUID> ids) {
        Map<UUID, Product> byId = new HashMap<>();
        products.findAllById(ids).forEach(product -> byId.put(product.getId(), product));
        return byId;
    }

    private static List<String> values(ProductOptions options, String axisKey) {
        return options.axis(axisKey).map(axis -> axis.values().stream().map(ProductOptions.Value::value).toList()).orElse(List.of());
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), KOREA);
    }
}
