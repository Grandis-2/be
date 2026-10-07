package com.grandis.nova.catalog.detail;

import com.grandis.nova.catalog.detail.ProductDetailView.Campaign;
import com.grandis.nova.catalog.detail.ProductDetailView.Image;
import com.grandis.nova.catalog.detail.ProductDetailView.ImageBundle;
import com.grandis.nova.catalog.detail.ProductDetailView.Images;
import com.grandis.nova.catalog.detail.ProductDetailView.OptionAxis;
import com.grandis.nova.catalog.detail.ProductDetailView.OptionValue;
import com.grandis.nova.catalog.detail.ProductDetailView.Variant;
import com.grandis.nova.catalog.detail.ProductDetailView.Warranty;
import com.grandis.nova.catalog.listing.CampaignWindow;
import com.grandis.nova.catalog.listing.ProductListingQueryRepository;
import com.grandis.nova.catalog.option.OptionCombination;
import com.grandis.nova.catalog.option.OptionText;
import com.grandis.nova.catalog.option.ProductOptions;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.ProductOption;
import com.grandis.nova.catalog.product.ProductOptionRepository;
import com.grandis.nova.catalog.product.ProductRepository;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 상품 상세 · 옵션 상세. 노출 규칙(설계 §2.2)은 목록과 다르다 —
 * 비공개(공개 여부 false 또는 판매 방식별 준비 전)는 누구에게나 404 다(관리자 미리보기 없음 — 관리자는 관리자 상세로 본다). 판매 중지는 200 에 상태 그대로.
 * 사전예약 마감 뒤 120시간이 지나 목록에서 빠진 상품도 직접 링크로는 보인다.
 *
 * 공개용 404 코드는 명세대로 공통 NOT_FOUND 다(내부 API 의 PRODUCT_NOT_FOUND 와 다르다). 비공개 상품과 없는 상품을 회원이 구분하지 못하게 같은 응답이다.
 * 한 상세가 여러 문장을 읽으므로 REPEATABLE READ 로 한 스냅샷을 쓴다.
 */
@Service
public class ProductDetailService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, String>> ATTRIBUTES = new TypeReference<>() {
    };

    private final ProductRepository products;
    private final ProductOptionRepository options;
    private final ProductListingQueryRepository crossReads;
    private final Clock clock;

    public ProductDetailService(ProductRepository products, ProductOptionRepository options,
                                ProductListingQueryRepository crossReads, Clock clock) {
        this.products = products;
        this.options = options;
        this.crossReads = crossReads;
        this.clock = clock;
    }

    /**
     * 회원 상세. 판매 방식별 준비(회차 · 재고 행)가 끝났고 공개인 상품만 — 아니면 누구에게나 404(관리자도). 관리자 미리보기는 없다(2026-09-29 결정) —
     * 관리자는 관리자 상세({@link #findAdminProduct})로 본다. 공개 경로는 폐기 조회 실패에 열리는 경로라 관리자 토큰을 여기서 더 믿지 않는다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductDetailView findProduct(Long productId) {
        return assemble(requireViewable(productId), true);
    }

    /**
     * 관리자 상세 — 노출 규칙 없이 어떤 상품이든(비공개 · 미완료 · 판매 중지 · 오래된 마감) 상세와 등록 상태를 준다. 없는 상품만 404.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminProductDetail findAdminProduct(Long productId) {
        Product product = products.findById(productId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        // 관리자 목록 · 상세의 visible 은 products.visible 칸 그대로 — 판매 방식별 준비는 registrationCompleted 가 따로 말한다
        return new AdminProductDetail(assemble(product, product.isVisible()), product.getTags(),
                product.getIdempotencyKey(), crossReads.isReady(productId));
    }

    /** @param visible 응답에 실을 visible — 회원 상세는 노출 규칙을 지났으니 늘 true, 관리자 상세는 칸 그대로 */
    private ProductDetailView assemble(Product product, boolean visible) {
        Long productId = product.getId();
        Instant now = clock.instant();
        ProductOptions document = product.getOptions();

        Map<Long, Integer> available = product.getSaleMode() == SaleMode.IN_STOCK
                ? crossReads.findAvailableQuantities(productId) : Map.of();
        List<Variant> variants = new ArrayList<>();
        boolean sellable = false;
        boolean purchasable = false;
        for (ProductOption option : options.findByProductIdOrderById(productId)) {
            Variant variant = toVariant(option, product.getSaleMode(), document, available);
            variants.add(variant);
            boolean active = option.getStatus() == SaleStatus.ACTIVE;
            sellable |= active;
            purchasable |= active && variant.availableQuantity() != null && variant.availableQuantity() > 0;
        }
        // 사전예약은 재고 행이 없으므로 품절이 되지 않는다(목록 SQL 과 같은 규칙)
        boolean soldOut = product.getSaleMode() == SaleMode.IN_STOCK && !purchasable;

        Campaign campaign = product.getSaleMode() == SaleMode.PREORDER
                ? crossReads.findCampaign(productId)
                        .map(window -> new Campaign(window.opensAt(), window.closesAt(), window.statusAt(now)))
                        .orElse(null)
                : null;
        Images productImages = imagesOf(document);

        return new ProductDetailView(product.getId(), product.getCategoryId(), product.getSaleMode(), product.getTitle(),
                product.getDescription(), product.getThumbnailUrl(), product.getStatus(), visible,
                product.getBasePrice(), new Warranty(product.isWarrantyOffered(), product.getWarrantySurcharge()),
                sellable, soldOut, campaign, toAxes(document), variants, productImages);
    }

    /** 옵션이 그 상품 소속이 아니면 404. 상품의 노출 규칙을 먼저 적용한다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Variant findVariant(Long productId, Long variantId) {
        return findProduct(productId).variants().stream()
                .filter(variant -> variant.variantId().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    /** 공개이고 판매 방식별 준비가 끝난 상품만. 아니면 404 — 준비 전에는 회차 · 재고가 없어 상세를 그릴 수 없다. */
    private Product requireViewable(Long productId) {
        Product product = products.findById(productId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (!(product.isVisible() && crossReads.isReady(productId))) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return product;
    }

    private static Variant toVariant(ProductOption option, SaleMode saleMode, ProductOptions document, Map<Long, Integer> available) {
        Integer availableQuantity = saleMode == SaleMode.IN_STOCK
                ? Math.max(0, available.getOrDefault(option.getId(), 0)) : null;
        // 고른 값은 조합 키(값 id)로 문서에서 찾는다 — 축 키 → 정규화값
        Map<String, String> selections = new TreeMap<>();
        for (ProductOptions.Pick pick : document.picksOf(OptionCombination.valueIdsOf(option.getCombinationKey()))) {
            selections.put(pick.axis().key(), pick.value().normalized());
        }
        return new Variant(option.getId(), option.getSku(), option.getTitle(), option.getPrice(),
                attributes(option.getFilterAttributes()), selections, option.getStatus(), availableQuantity);
    }

    private static Map<String, String> attributes(String json) {
        return json == null ? Map.of() : JSON.readValue(json, ATTRIBUTES);
    }

    private static List<OptionAxis> toAxes(ProductOptions document) {
        return document.axes().stream()
                .map(axis -> new OptionAxis(axis.key(), axis.label(), axis.values().stream()
                        .map(value -> new OptionValue(value.id(), value.value(), value.normalized(), value.hex(), value.surcharge()))
                        .toList()))
                .toList();
    }

    /** 색상 축이 있으면 사진이 있는 색상 값마다 묶음 하나(bundleKey = 정규화값, 값 순), 없으면 기본 묶음 하나(bundleKey ""). 상세는 영역 순. */
    private static Images imagesOf(ProductOptions document) {
        List<ImageBundle> gallery = new ArrayList<>();
        document.axis(OptionText.COLOR).ifPresentOrElse(
                color -> color.values().stream()
                        .filter(value -> !value.images().isEmpty())
                        .forEach(value -> gallery.add(new ImageBundle(value.normalized(), items(value.images())))),
                () -> {
                    if (!document.defaultImages().isEmpty()) {
                        gallery.add(new ImageBundle("", items(document.defaultImages())));
                    }
                });
        List<ImageBundle> detail = document.detailImages().stream()
                .map(section -> new ImageBundle(section.section(), items(section.images())))
                .toList();
        return new Images(gallery, detail);
    }

    private static List<Image> items(List<ProductOptions.Image> images) {
        List<Image> items = new ArrayList<>();
        for (int position = 0; position < images.size(); position++) {
            items.add(new Image(images.get(position).url(), position, images.get(position).primary()));
        }
        return items;
    }
}
