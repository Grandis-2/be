package com.grandis.nova.catalog.detail;

import com.grandis.nova.catalog.detail.ProductDetailView.Campaign;
import com.grandis.nova.catalog.detail.ProductDetailView.Image;
import com.grandis.nova.catalog.detail.ProductDetailView.ImageBundle;
import com.grandis.nova.catalog.detail.ProductDetailView.Images;
import com.grandis.nova.catalog.detail.ProductDetailView.OptionAxis;
import com.grandis.nova.catalog.detail.ProductDetailView.OptionValue;
import com.grandis.nova.catalog.detail.ProductDetailView.Variant;
import com.grandis.nova.catalog.detail.ProductDetailView.Warranty;
import com.grandis.nova.catalog.integration.PreorderCampaigns;
import com.grandis.nova.catalog.integration.StockReader;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
 * 상세는 본문(catalog 의 칸 — 한 스냅샷으로 REPEATABLE READ)과 실시간 칸(다른 서비스가 바꾸는 회차 · 재고 — 요청마다 한 번)으로 나뉜다.
 * 본문은 읽기 캐시에 길게 두고, 실시간 칸은 캐시하지 않는다(CatalogReadCache). 일반 상품의 판매 준비(products.stock_ready_at)는 catalog 의 칸이라
 * 본문에 싣는다 — 판매 준비 이벤트가 채울 때 상품 세대를 올린다(InStockProductReadyHandler).
 */
@Service
public class ProductDetailService {

    private static final Logger log = LoggerFactory.getLogger(ProductDetailService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, String>> ATTRIBUTES = new TypeReference<>() {
    };

    private final ProductRepository products;
    private final ProductOptionRepository options;
    private final ProductListingQueryRepository crossReads;
    private final StockReader stocks;
    private final PreorderCampaigns campaigns;
    private final Clock clock;
    /** DB 를 읽는 부분만 감싼다 — order 재고 · preorder 회차를 묻는 동안 DB 연결을 붙잡지 않게. */
    private final TransactionTemplate snapshot;

    public ProductDetailService(ProductRepository products, ProductOptionRepository options,
                                ProductListingQueryRepository crossReads, StockReader stocks, PreorderCampaigns campaigns, Clock clock,
                                PlatformTransactionManager transactionManager) {
        this.products = products;
        this.options = options;
        this.crossReads = crossReads;
        this.stocks = stocks;
        this.campaigns = campaigns;
        this.clock = clock;
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * 회원 상세 = 상품 본문({@link #findProductBody}) + 실시간 칸({@link #withLiveParts}). 본문은 한 트랜잭션에서 읽고, 실시간 칸은 그 뒤에 채운다
     * (재고 수량은 order 가 답한 시점의 값이다). 캐시를 거치는 공개 상세는 CatalogReadCache 가 둘을 따로 부른다 — 이 메서드는 시험이 쓴다.
     */
    public ProductDetailView findProduct(UUID productId) {
        return withLiveParts(snapshot.execute(status -> findProductBody(productId)));
    }

    /**
     * 회원 상세의 본문 — catalog 가 정하는 칸만 채운다(상품 · 옵션 · 가격 · 할인율 · 사진 · 보증 · 판매 상태 · 일반 상품의 판매 준비). 다른 서비스가 바꾸는
     * 칸(재고 수량 · 품절 · 회차)은 비운 채 두고 {@link #withLiveParts} 가 요청마다 채운다. 그래서 길게 캐시해도 된다 — catalog 가 고치면(판매 준비 이벤트
     * 포함) 읽기 캐시 세대가 오른다. 판매 준비는 같은 트랜잭션에서 읽는다.
     * 공개가 아니면 누구에게나 404(관리자도). 관리자 미리보기는 없다(2026-09-29 결정) — 관리자는 관리자 상세({@link #findAdminProduct})로 본다.
     * 공개 경로는 폐기 조회 실패에 열리는 경로라 관리자 토큰을 여기서 더 믿지 않는다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductDetailBody findProductBody(UUID productId) {
        Product product = products.findById(productId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (!product.isVisible()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        boolean stockReady = product.getSaleMode() == SaleMode.IN_STOCK && crossReads.isStockReady(productId);
        return new ProductDetailBody(body(product, true), stockReady);
    }

    /**
     * 본문에 실시간 칸을 채운다. 사전예약은 preorder 의 회차(판매 준비 · 회차 시각 · 접수 단계), 일반은 그 상품 옵션의 재고 행(옵션별 가용 수량 · 품절).
     * 판매 준비 전이면 404 — 목록의 판매 방식별 준비와 같은 정의다. 일반 상품의 판매 준비는 본문에 실린 값이다(DB 를 다시 읽지 않는다).
     * 캐시된 본문에도 요청마다 부르므로 재고 · 조기 마감이 늦게 보이지 않는다. 트랜잭션 없이 돈다 — order 재고 · preorder 회차를 묻는 동안 DB 연결을
     * 붙잡지 않게.
     *
     * @throws BusinessException 사전예약인데 preorder 가 답하지 않으면 503 — 접수 단계를 가를 수 없다(2026-10-19 결정)
     */
    public ProductDetailView withLiveParts(ProductDetailBody body) {
        ProductDetailView view = body.view();
        if (view.saleMode() == SaleMode.PREORDER) {
            CampaignWindow window = campaigns.find(view.productId())
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
            return live(view, window, Map.of());
        }
        // 판매 준비는 목록과 같은 판정(products.stock_ready_at — order 의 판매 준비 이벤트)이다. 재고 행의 유무로 따로 가르면 목록에 없는 상품이 상세에 보인다
        if (!body.stockReady()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return live(view, null, availableOrUnknown(view));
    }

    /**
     * 관리자 상세 — 노출 규칙 없이 어떤 상품이든(비공개 · 미완료 · 판매 중지 · 오래된 마감) 상세와 등록 상태를 준다. 없는 상품 · 삭제한 상품만 404.
     * DB 는 한 트랜잭션에서 읽고({@link #findAdminSnapshot}), 다른 서비스의 칸(일반 상품의 남은 수량 · 사전예약의 회차)은 그 뒤에 묻는다({@link #withLive}).
     * 쓰기 트랜잭션 안에서 부르지 않는다 — 그 안에서는 {@link #findAdminSnapshot} 을 쓰고, 그 칸은 커밋 뒤에 채운다.
     */
    public AdminProductDetail findAdminProduct(UUID productId) {
        return withLive(snapshot.execute(status -> findAdminSnapshot(productId)));
    }

    /**
     * 관리자 상세의 DB 부분 — 일반 상품의 남은 수량은 비운 채(null · 품절 아님), 사전예약은 회차 없이 준비 전(registrationCompleted=false)이다.
     * 상품 행을 잠근 쓰기 흐름이 응답을 만들 때 쓴다: 잠금 · 연결을 쥔 채 order · preorder 를 부르지 않게, 그 칸은 커밋 뒤에 {@link #withLive} 나
     * {@link #beforeStockReady} 로 채운다. 혼자 불리면(GET) REPEATABLE READ 한
     * 스냅샷이고, 쓰기 트랜잭션 안에서는 그 트랜잭션에 합류한다(격리 수준은 바깥 것 — 상품 · 옵션 행은 그 쓰기가 잠가 두었다).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminProductDetail findAdminSnapshot(UUID productId) {
        Product product = products.findById(productId).filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        // 관리자 목록 · 상세의 visible 은 products.visible 칸 그대로 — 판매 방식별 준비는 registrationCompleted 가 따로 말한다
        ProductDetailView body = body(product, product.isVisible());
        boolean preorder = product.getSaleMode() == SaleMode.PREORDER;
        ProductDetailView detail = live(body, null, preorder ? Map.of() : null);
        return new AdminProductDetail(detail, product.getTags(), product.getIdempotencyKey(), !preorder && crossReads.isStockReady(productId));
    }

    /**
     * 다른 서비스의 칸을 채운다. 트랜잭션 밖에서 부른다.
     * <ul>
     *   <li>사전예약 — preorder 의 회차(시각 · 접수 단계)와 판매 준비(회차가 있다). 관리자 화면이라 preorder 가 답하지 않으면 503 이다.</li>
     *   <li>일반 — 옵션별 남은 수량을 order 에 묻는다(답하지 않으면 수량 null).</li>
     * </ul>
     */
    public AdminProductDetail withLive(AdminProductDetail detail) {
        ProductDetailView product = detail.product();
        if (product.saleMode() == SaleMode.PREORDER) {
            CampaignWindow window = campaigns.find(product.productId()).orElse(null);
            return new AdminProductDetail(live(product, window, Map.of()), detail.tags(), detail.registrationKey(), window != null);
        }
        return new AdminProductDetail(live(product, null, availableOrUnknown(product)), detail.tags(), detail.registrationKey(),
                detail.registrationCompleted());
    }

    /**
     * 관리자 쓰기의 응답 — {@link #withLive} 와 같되, 사전예약의 회차를 preorder 에 묻지 못하면(일시 장애) 회차 null · 준비 false(DB 부분 그대로)로 낸다 —
     * 이때 준비 false 는 "준비 전" 이 아니라 "모름" 이다. 쓰기는
     * 이미 커밋됐으므로 503 으로 끝내지 않는다 — 일반 상품의 남은 수량을 비우는 것과 같은 방식(2026-10-19 결정). 관리자 상세 조회는 503 이다.
     */
    public AdminProductDetail afterWrite(AdminProductDetail detail) {
        if (detail.product().saleMode() != SaleMode.PREORDER) {
            return withLive(detail);
        }
        try {
            return withLive(detail);
        } catch (BusinessException e) {
            if (e.errorCode() != CommonErrorCode.DEPENDENCY_UNAVAILABLE) {
                throw e;   // 계약 위반 등 연동 오류는 그대로 — "답하지 않을 때" 만 비운다
            }
            log.warn("수정한 사전예약 상품의 회차를 preorder 에 묻지 못했다 — 회차를 비워 보낸다 productId={}", detail.product().productId(), e);
            return detail;
        }
    }

    /**
     * 수정한 옵션 하나에 남은 수량을 채운다 — 수정 트랜잭션은 수량을 묻지 않으므로(상품 행을 잠근 채 order 를 부르지 않게) 커밋 뒤에 그 옵션 하나만
     * order 에 묻는다. 사전예약은 그대로(null). order 가 답하지 않으면 수량 null.
     */
    public Variant withStock(UUID productId, Variant variant) {
        boolean preorder = products.findById(productId).map(product -> product.getSaleMode() == SaleMode.PREORDER).orElse(true);
        if (preorder) {
            return variant;
        }
        try {
            return variant.withAvailableQuantity(stocks.available(List.of(variant.variantId())).getOrDefault(variant.variantId(), 0));
        } catch (BusinessException e) {
            log.warn("수정한 옵션의 남은 수량을 order 에 묻지 못했다 — 수량을 비워 보낸다 variantId={}", variant.variantId(), e);
            return variant.withAvailableQuantity(null);
        }
    }

    /**
     * 재고가 아직 없는 일반 상품 — 옵션 수량 0 · 품절. 등록 · 사전예약 전환의 응답이 쓴다: 재고는 커밋 뒤 나가는 이벤트로 order 가 만드므로, 그
     * 트랜잭션 안에서는 order 에 물을 것이 없다.
     */
    public AdminProductDetail beforeStockReady(AdminProductDetail detail) {
        ProductDetailView product = detail.product();
        if (product.saleMode() == SaleMode.PREORDER) {
            return detail;
        }
        return new AdminProductDetail(live(product, null, Map.of()), detail.tags(), detail.registrationKey(), detail.registrationCompleted());
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
     * 옵션별 남은 수량을 order 에 묻는다(재고 행이 없는 옵션은 빠진다). order 가 답하지 않으면 null — 수량을 모름(2026-10-19 결정: 상세는 뜨고 수량만 비운다).
     */
    private Map<UUID, Integer> availableOrUnknown(ProductDetailView body) {
        try {
            return stocks.available(body.variants().stream().map(Variant::variantId).toList());
        } catch (BusinessException e) {
            log.warn("상세의 남은 수량을 order 에 묻지 못했다 — 수량을 비워 보낸다 productId={}", body.productId(), e);
            return null;
        }
    }

    /**
     * 본문 + 실시간 칸. 일반 상품은 옵션마다 남은 수량(order 답에 없으면 0)과 품절(판매 중 옵션의 남은 수량이 전부 0), 사전예약은 회차(window 가
     * 없으면 null)와 지금 시각의 접수 단계. 사전예약은 재고가 없으므로 품절이 되지 않는다.
     *
     * @param available 옵션 id → 남은 수량. null 이면 수량을 모른다(order 가 답하지 않았거나 아직 묻지 않았다) — 옵션 수량은 null, 품절은 false
     */
    private ProductDetailView live(ProductDetailView body, CampaignWindow window, Map<UUID, Integer> available) {
        if (body.saleMode() == SaleMode.PREORDER) {
            Campaign campaign = window == null ? null : new Campaign(window.opensAt(), window.closesAt(), window.statusAt(clock.instant()));
            return body.withLive(campaign, body.variants(), false);
        }
        if (available == null) {
            return body.withLive(null, body.variants().stream().map(variant -> variant.withAvailableQuantity(null)).toList(), false);
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
