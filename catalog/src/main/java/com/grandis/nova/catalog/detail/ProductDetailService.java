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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 상품 상세 · 옵션 상세. 노출 규칙(설계 §2.2)은 목록과 다르다 —
 * 비공개(공개 여부 false 또는 판매 방식별 준비 전)는 누구에게나 404 다(관리자 미리보기 없음 — 관리자는 관리자 상세로 본다). 판매 중지는 200 에 상태 그대로.
 * 사전예약은 목록에 접수 중일 때만 나오지만, 오픈 전 · 마감 뒤에도 직접 링크(홍보 페이지의 "예약하기")로는 보인다.
 *
 * 공개용 404 코드는 명세대로 공통 NOT_FOUND 다(내부 API 의 PRODUCT_NOT_FOUND 와 다르다). 비공개 상품과 없는 상품을 회원이 구분하지 못하게 같은 응답이다.
 * 상세는 본문(catalog 의 칸 — 한 스냅샷으로 REPEATABLE READ)과 실시간 칸(다른 서비스가 바꾸는 판매 준비 · 회차 · 재고 — 요청마다 한 번)으로 나뉜다.
 * 본문은 읽기 캐시에 길게 두고, 실시간 칸은 캐시하지 않는다(CatalogReadCache).
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
     * 회원 상세 = 상품 본문({@link #findProductBody}) + 실시간 칸({@link #withLiveParts}), 한 스냅샷. 같은 객체 안의 호출이라 두 메서드의
     * 트랜잭션 설정은 프록시를 거치지 않는다 — 여기서 한 트랜잭션으로 묶는다. 캐시를 거치는 공개 상세는 CatalogReadCache 가 둘을 따로 부른다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductDetailView findProduct(UUID productId) {
        return withLiveParts(findProductBody(productId));
    }

    /**
     * 회원 상세의 본문 — catalog 가 정하는 칸만 채운다(상품 · 옵션 · 가격 · 할인율 · 사진 · 보증 · 판매 상태). 다른 서비스가 바꾸는 칸(재고 수량 · 품절 ·
     * 회차)은 비운 채 두고 {@link #withLiveParts} 가 요청마다 채운다. 그래서 길게 캐시해도 된다 — catalog 가 고치면 읽기 캐시 세대가 오른다.
     * 공개가 아니면 누구에게나 404(관리자도). 관리자 미리보기는 없다(2026-09-29 결정) — 관리자는 관리자 상세({@link #findAdminProduct})로 본다.
     * 공개 경로는 폐기 조회 실패에 열리는 경로라 관리자 토큰을 여기서 더 믿지 않는다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductDetailView findProductBody(UUID productId) {
        Product product = products.findById(productId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (!product.isVisible()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return body(product, true);
    }

    /**
     * 본문에 실시간 칸을 채운다 — 판매 방식마다 한 번 읽는다. 사전예약은 회차 행(판매 준비 · 회차 시각 · 접수 단계), 일반은 그 상품 옵션의 재고 행
     * (판매 준비 · 옵션별 가용 수량 · 품절). 행이 없으면 판매 준비 전이라 404 — 목록의 판매 방식별 준비(READY)와 같은 정의다.
     * 캐시된 본문에도 요청마다 부르므로 재고 · 조기 마감 · 판매 준비가 늦게 보이지 않는다.
     */
    @Transactional(readOnly = true)
    public ProductDetailView withLiveParts(ProductDetailView body) {
        if (body.saleMode() == SaleMode.PREORDER) {
            CampaignWindow window = crossReads.findCampaign(body.productId())
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
            return live(body, window, Map.of());
        }
        Map<UUID, Integer> available = crossReads.findAvailableQuantities(body.productId());
        if (available.isEmpty()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return live(body, null, available);
    }

    /**
     * 관리자 상세 — 노출 규칙 없이 어떤 상품이든(비공개 · 미완료 · 판매 중지 · 오래된 마감) 상세와 등록 상태를 준다. 없는 상품 · 삭제한 상품만 404.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminProductDetail findAdminProduct(UUID productId) {
        Product product = products.findById(productId).filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        // 관리자 목록 · 상세의 visible 은 products.visible 칸 그대로 — 판매 방식별 준비는 registrationCompleted 가 따로 말한다
        ProductDetailView body = body(product, product.isVisible());
        ProductDetailView detail = product.getSaleMode() == SaleMode.PREORDER
                ? live(body, crossReads.findCampaign(productId).orElse(null), Map.of())
                : live(body, null, crossReads.findAvailableQuantities(productId));
        return new AdminProductDetail(detail, product.getTags(), product.getIdempotencyKey(), crossReads.isReady(productId));
    }

    /** @param visible 응답에 실을 visible — 회원 상세는 노출 규칙을 지났으니 늘 true, 관리자 상세는 칸 그대로 */
    private ProductDetailView body(Product product, boolean visible) {
        ProductOptions document = product.getOptions();
        List<Variant> variants = new ArrayList<>();
        boolean sellable = false;
        for (ProductOption option : options.findByProductIdOrderByCreatedAtAscIdAsc(product.getId())) {
            variants.add(toVariant(option, product, document));
            sellable |= option.getStatus() == SaleStatus.ACTIVE;
        }
        return new ProductDetailView(product.getId(), product.getCategoryId(), product.getSaleMode(), product.getTitle(),
                product.getBrand(), product.getModelName(),
                product.getDescription(), product.getThumbnailUrl(), product.getStatus(), visible,
                product.getBasePrice(), product.getDiscountRate(), new Warranty(product.isWarrantyOffered(), product.getWarrantySurcharge()),
                sellable, false, null, toAxes(document), variants, imagesOf(document));
    }

    /**
     * 본문 + 실시간 칸. 일반 상품은 옵션마다 가용 수량(재고 행이 없으면 0)과 품절(판매 중 옵션의 가용이 전부 0 이하 — 목록 SQL 과 같은 규칙),
     * 사전예약은 회차(window 가 없으면 null)와 지금 시각의 접수 단계. 사전예약은 재고 행이 없으므로 품절이 되지 않는다.
     */
    private ProductDetailView live(ProductDetailView body, CampaignWindow window, Map<UUID, Integer> available) {
        if (body.saleMode() == SaleMode.PREORDER) {
            Campaign campaign = window == null ? null : new Campaign(window.opensAt(), window.closesAt(), window.statusAt(clock.instant()));
            return body.withLive(campaign, body.variants(), false);
        }
        List<Variant> variants = new ArrayList<>();
        boolean purchasable = false;
        for (Variant variant : body.variants()) {
            int quantity = Math.max(0, available.getOrDefault(variant.variantId(), 0));
            variants.add(variant.withAvailableQuantity(quantity));
            purchasable |= variant.status() == SaleStatus.ACTIVE && quantity > 0;
        }
        return body.withLive(null, variants, !purchasable);
    }

    /** 옵션이 그 상품 소속이 아니면 404. 상품의 노출 규칙을 먼저 적용한다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Variant findVariant(UUID productId, UUID variantId) {
        return findProduct(productId).variants().stream()
                .filter(variant -> variant.variantId().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    /** 옵션 하나. 가용 수량은 비운 채(null) — 일반 상품은 {@link #live} 가 채운다. */
    private static Variant toVariant(ProductOption option, Product product, ProductOptions document) {
        // 고른 값은 조합 키(값 id)로 문서에서 찾는다 — 축 키 → 정규화값
        Map<String, String> selections = new TreeMap<>();
        for (ProductOptions.Pick pick : document.picksOf(OptionCombination.valueIdsOf(option.getCombinationKey()))) {
            selections.put(pick.axis().key(), pick.value().normalized());
        }
        return new Variant(option.getId(), option.getSku(), option.getTitle(), product.salePrice(option.getPrice()), option.getPrice(),
                attributes(option.getFilterAttributes()), selections, option.getStatus(), null);
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
