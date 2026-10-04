package com.grandis.nova.catalog.edit;

import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.catalog.detail.AdminProductDetail;
import com.grandis.nova.catalog.detail.ProductDetailService;
import com.grandis.nova.catalog.detail.ProductDetailView;
import com.grandis.nova.catalog.image.ImageKind;
import com.grandis.nova.catalog.image.ProductImageRepository;
import com.grandis.nova.catalog.listing.ProductListingQueryRepository;
import com.grandis.nova.catalog.option.OptionCombination;
import com.grandis.nova.catalog.option.OptionCombination.Pick;
import com.grandis.nova.catalog.option.ProductOptionAxis;
import com.grandis.nova.catalog.option.ProductOptionAxisRepository;
import com.grandis.nova.catalog.option.ProductOptionSelection;
import com.grandis.nova.catalog.option.ProductOptionSelectionRepository;
import com.grandis.nova.catalog.option.ProductOptionValue;
import com.grandis.nova.catalog.option.ProductOptionValueRepository;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.ProductOption;
import com.grandis.nova.catalog.product.ProductOptionRepository;
import com.grandis.nova.catalog.product.ProductRepository;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;
import com.grandis.nova.catalog.registration.ProductRegistrationValidator;
import com.grandis.nova.catalog.web.ConstraintViolations;
import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.outbox.OutboxWriter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 상품 수정 — 표시 정보 · 기본 가격 · 보증, 옵션 값 추가 · 수정, 옵션(조합) 추가 · 수정, 상품 판매 상태 · 공개 여부.
 *
 * <p><b>사전예약은 오픈 3분 전부터 공개 여부 말고는 아무것도 못 바꾼다.</b> 표시 정보 · 가격 · 추가금 · 값 · 조합 추가 · 가격 되돌리기 · 옵션 판매 상태 · 상품 판매 상태 전부
 * 409 STATE_CONFLICT(2026-09-29 결정 — 판매 상태도 막는다. preorder 는 접수용 상품 사본을 1분마다 새로 받아, 오픈 뒤 판매 중지는 접수에
 * 늦게 닿는다. 3분 전에 막으면 오픈 때 preorder 사본은 이미 최종 상태다 — preorder 의 1분 새로 받기가 성공할 때다. 새로 받기가 실패하면 preorder 는
 * 가진 값을 만료(30분)까지 쓴다(preorder CatalogReader). 그 틈은 catalog 가 닫을 수 없다 — preorder 캐시를 비울 경로가 없다). 판정은 preorder 의 회차(opens_at − 3분 ≤ 지금)로 하고, 회차가 없으면
 * (preorder 가 등록 이벤트를 처리하기 전) 아직 잠기지 않았다.
 *
 * <p><b>재계산.</b> 기본 가격 · 추가금이 바뀌면 그 값을 고른 옵션 중 수동 가격이 아닌 것만 `기본가 + Σ추가금` 으로 다시 계산한다. 관리자가 직접 고친
 * 가격(priceOverridden)은 그대로 둔다.
 *
 * <p><b>구성은 바꾸지 않는다.</b> 값의 정규화값 · 옵션의 조합 · sku 는 불변이다. 실제 색상 · 용량 구성이 바뀌면 값을 더하고 새 조합을 만들고 옛 옵션을
 * 판매 중지한다. 옛 옵션에 주문 이력이 있는지는 catalog 가 볼 수 없다(주문 표를 읽지 않는다) — 그래서 지우지 않고 상태로 숨긴다.
 *
 * <p><b>한 상품의 수정은 줄 선다.</b> 수정은 모두 상품 행을 잠그고(SELECT … FOR UPDATE) 시작한다. 오픈 판정은 시작 때 한 번, 커밋
 * 직전에 한 번 더 한다(잠금 대기 · 재계산 중에 오픈 시각이 지날 수 있다).
 *
 * <p>일반 상품의 새 옵션 재고는 여기서 받지 않는다 — 재고는 order 의 `PUT /admin/products/{id}/stock` 이 정본이다. 재고 행이 없는 동안 그 옵션은 품절로 보인다.
 */
@Service
public class ProductEditService {

    /**
     * 사전예약은 오픈 이 시간 전부터 수정을 막는다(2026-09-29 결정). 오픈 시각에 딱 맞춰 막으면 커밋 직전 판정과 커밋 사이 수 ms 와 서버 간 시계 차가
     * 틈으로 남는다 — 여유를 두어 그 틈에서 오픈이 일어날 수 없게 한다. 시작 판정과 커밋 직전 판정이 같은 기준을 쓴다(requireNotOpened 하나).
     */
    public static final Duration FREEZE_BEFORE_OPEN = Duration.ofMinutes(3);

    private static final String EDIT_FROZEN = "사전예약 오픈 3분 전부터는 상품 정보 · 옵션 · 가격을 바꿀 수 없습니다.";
    private static final String STATUS_FROZEN = "사전예약 오픈 3분 전부터는 판매 상태를 바꿀 수 없습니다.";

    private final ProductRepository products;
    private final ProductOptionAxisRepository axes;
    private final ProductOptionValueRepository values;
    private final ProductOptionRepository options;
    private final ProductOptionSelectionRepository selections;
    private final ProductListingQueryRepository crossReads;
    private final ProductImageRepository images;
    private final ProductDetailService detailService;
    private final OutboxWriter outbox;
    private final Clock clock;

    public ProductEditService(ProductRepository products, ProductOptionAxisRepository axes, ProductOptionValueRepository values,
                              ProductOptionRepository options, ProductOptionSelectionRepository selections,
                              ProductListingQueryRepository crossReads, ProductImageRepository images,
                              ProductDetailService detailService, OutboxWriter outbox, Clock clock) {
        this.products = products;
        this.axes = axes;
        this.values = values;
        this.options = options;
        this.selections = selections;
        this.crossReads = crossReads;
        this.images = images;
        this.detailService = detailService;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public AdminProductDetail editProduct(Long productId, ProductEditRequest request) {
        if (request.isEmpty()) {
            throw ValidationFailures.of("body", "바꿀 칸이 하나도 없습니다.");
        }
        if (request.title() != null && request.title().isBlank()) {
            throw ValidationFailures.of("title", "제목이 비었습니다.");
        }
        if (request.basePrice() != null) {
            ProductRegistrationValidator.requireWholeWon(request.basePrice(), "basePrice");
        }
        ProductEditRequest.Warranty warranty = request.warranty();
        if (warranty != null && warranty.surcharge() != null) {
            ProductRegistrationValidator.requireWholeWon(warranty.surcharge(), "warranty.surcharge");
            if (!warranty.offered() && warranty.surcharge().signum() > 0) {
                throw ValidationFailures.of("warranty.surcharge", "보증을 제공하지 않으면 추가금은 0 이어야 합니다.");
            }
        }
        Product product = requireEditable(productId);
        String titleBefore = product.getTitle();
        product.edit(request.title(), request.description(), request.tags());
        if (!product.getTitle().equals(titleBefore)) {
            retitleStandaloneOptions(product);   // 축 없는 상품의 옵션 표시명은 상품 제목이다
        }
        if (warranty != null) {
            // 보낸 칸만 바뀐다 — 제공만 보내면 기존 추가금을 유지한다. 제공하지 않으면 추가금은 0 이다(엔티티가 지킨다)
            product.setWarranty(warranty.offered(), warranty.surcharge() == null ? product.getWarrantySurcharge() : warranty.surcharge());
        }
        if (request.basePrice() != null && product.reprice(request.basePrice())) {
            recomputePrices(product, null);
        }
        products.flush();
        AdminProductDetail edited = detailService.findAdminProduct(productId);
        requireNotOpenedAtCommit(product);
        return edited;
    }

    @Transactional
    public AdminProductDetail addOptionValue(Long productId, OptionValueAddRequest request) {
        BigDecimal surcharge = request.surcharge() == null ? BigDecimal.ZERO
                : ProductRegistrationValidator.requireWholeWon(request.surcharge(), "surcharge");
        Product product = requireEditable(productId);
        String axisKey = request.axisKey().strip().toLowerCase(Locale.ROOT);
        ProductOptionAxis axis = axes.findByProductIdOrderByPosition(productId).stream()
                .filter(a -> a.getAxisKey().equals(axisKey)).findFirst()
                .orElseThrow(() -> ValidationFailures.of("axisKey", "이 상품에 없는 축입니다: " + request.axisKey()));
        List<ProductOptionValue> existing = values.findByAxisIdInOrderByAxisIdAscPositionAsc(List.of(axis.getId()));
        String normalized = normalized(axisKey, request.value(), "value");
        if (ProductOptionAxis.STORAGE.equals(axisKey) && !ProductRegistrationValidator.STORAGE.matcher(normalized).matches()) {
            throw ValidationFailures.of("value", "용량은 숫자 + MB/GB/TB 로 적습니다.");
        }
        String key = ProductRegistrationValidator.collationKey(normalized);
        if (existing.stream().anyMatch(v -> ProductRegistrationValidator.collationKey(v.getNormalizedValue()).equals(key))) {
            throw ValidationFailures.of("value", "같은 값이 이미 있습니다.");
        }
        int position = existing.stream().mapToInt(ProductOptionValue::getPosition).max().orElse(-1) + 1;
        try {
            values.saveAndFlush(ProductOptionValue.of(axis.getId(), request.value(), normalized, surcharge, position));
        } catch (DataIntegrityViolationException e) {
            // 콜레이션 흉내가 못 잡는 확장 문자(ß=ss …)는 DB UNIQUE 가 최종 판정한다. 다른 제약 위반은 그대로 올린다
            if (ConstraintViolations.mentionsKey(e, "uq_option_value")) {
                throw ValidationFailures.of("value", "같은 값이 이미 있습니다.");
            }
            throw e;
        }
        AdminProductDetail edited = detailService.findAdminProduct(product.getId());
        requireNotOpenedAtCommit(product);
        return edited;
    }

    @Transactional
    public AdminProductDetail editOptionValue(Long productId, Long valueId, OptionValueEditRequest request) {
        if (request.isEmpty()) {
            throw ValidationFailures.of("body", "바꿀 칸이 하나도 없습니다.");
        }
        if (request.surcharge() != null) {
            ProductRegistrationValidator.requireWholeWon(request.surcharge(), "surcharge");
        }
        Product product = requireEditable(productId);
        Map<Long, ProductOptionAxis> axisById = axesOf(productId);
        ProductOptionValue value = values.findById(valueId)
                .filter(v -> axisById.containsKey(v.getAxisId()))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        String axisKey = axisById.get(value.getAxisId()).getAxisKey();
        if (request.value() != null) {
            renameValue(product, axisKey, value, request.value());
        }
        if (request.surcharge() != null) {
            value.reprice(request.surcharge());
            recomputePrices(product, value.getId());
        }
        values.flush();
        AdminProductDetail edited = detailService.findAdminProduct(productId);
        requireNotOpenedAtCommit(product);
        return edited;
    }

    @Transactional
    public ProductDetailView.Variant addVariant(Long productId, VariantAddRequest request) {
        boolean overridden = request.price() != null;
        if (overridden) {
            ProductRegistrationValidator.requireWholeWon(request.price(), "price");
        }
        Map<String, String> given = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : request.selections().entrySet()) {
            String axisKey = entry.getKey() == null ? "" : entry.getKey().strip().toLowerCase(Locale.ROOT);
            if (given.put(axisKey, entry.getValue()) != null) {
                // "Color" 와 "color" 가 같이 오면 뒤의 것이 조용히 이기지 않게
                throw ValidationFailures.of("selections", "같은 축이 두 번 왔습니다(키는 대소문자를 가리지 않습니다): " + axisKey);
            }
        }
        Product product = requireEditable(productId);
        List<ProductOptionAxis> productAxes = axes.findByProductIdOrderByPosition(productId);
        List<Pick> picks = new ArrayList<>();
        List<ProductOptionValue> picked = new ArrayList<>();
        for (ProductOptionAxis axis : productAxes) {
            if (!given.containsKey(axis.getAxisKey())) {
                throw ValidationFailures.of("selections", "축 " + axis.getAxisKey() + " 의 값이 없습니다.");
            }
            String raw = given.remove(axis.getAxisKey());
            String field = "selections." + axis.getAxisKey();
            String normalized = normalized(axis.getAxisKey(), raw, field);
            ProductOptionValue value = values.findByAxisIdInOrderByAxisIdAscPositionAsc(List.of(axis.getId())).stream()
                    .filter(v -> v.getNormalizedValue().equals(normalized)).findFirst()
                    .orElseThrow(() -> ValidationFailures.of(field, "축에 없는 값입니다: " + raw + " (값을 먼저 더하세요: POST …/option-values)"));
            picks.add(new Pick(axis, value));
            picked.add(value);
        }
        if (!given.isEmpty()) {
            throw ValidationFailures.of("selections", "이 상품에 없는 축입니다: " + given.keySet());
        }
        OptionCombination combination = picks.isEmpty()
                ? OptionCombination.none(productId, product.getTitle()) : OptionCombination.of(productId, picks);
        List<ProductOption> existing = options.findByProductIdOrderById(productId);
        if (existing.stream().anyMatch(o -> combination.combinationKey().equals(o.getCombinationKey()))) {
            throw ValidationFailures.of("selections", "이 조합의 옵션이 이미 있습니다.");
        }
        String sku = request.sku() == null || request.sku().isBlank() ? defaultSku(picked) : request.sku().strip();
        if (sku.length() > ProductRegistrationValidator.MAX_SKU_LENGTH) {
            // 기본값(정규화값을 '-' 로 이은 것)도 길 수 있다 — 직접 주는 SKU 는 @Size 가 먼저 막는다
            throw ValidationFailures.of("sku", "SKU 는 %d자 이하입니다: %s".formatted(ProductRegistrationValidator.MAX_SKU_LENGTH, sku));
        }
        if (existing.stream().anyMatch(o -> o.getSku().equals(sku))) {
            throw ValidationFailures.of("sku", "같은 SKU 가 이미 있습니다.");
        }
        if (combination.title().length() > ProductRegistrationValidator.MAX_OPTION_TITLE_LENGTH) {
            throw ValidationFailures.of("selections", "옵션 표시명이 %d자를 넘습니다.".formatted(ProductRegistrationValidator.MAX_OPTION_TITLE_LENGTH));
        }
        BigDecimal price = overridden ? request.price() : computedPrice(product, picked);
        ProductOption option;
        try {
            option = options.saveAndFlush(ProductOption.of(sku, price, overridden, combination));
        } catch (DataIntegrityViolationException e) {
            // 위 검사를 지나 DB 에서 난 UNIQUE 위반 — 같은 상품 수정은 상품 행 잠금으로 줄 서므로 남는 것은 잠금 밖의 쓰기뿐이다
            if (ConstraintViolations.mentionsKey(e, "uq_option_combination")) {
                throw ValidationFailures.of("selections", "이 조합의 옵션이 이미 있습니다.");
            }
            if (ConstraintViolations.mentionsKey(e, "uq_option_sku")) {
                throw ValidationFailures.of("sku", "같은 SKU 가 이미 있습니다.");
            }
            throw e;
        }
        selections.saveAll(combination.selections(option.getId()));
        selections.flush();
        ProductDetailView.Variant added = variantOf(productId, option.getId());
        requireNotOpenedAtCommit(product);
        return added;
    }

    @Transactional
    public ProductDetailView.Variant editVariant(Long productId, Long variantId, VariantEditRequest request) {
        if (request.isEmpty()) {
            throw ValidationFailures.of("body", "바꿀 칸이 하나도 없습니다.");
        }
        if (request.price() != null) {
            ProductRegistrationValidator.requireWholeWon(request.price(), "price");
            if (request.resets()) {
                throw ValidationFailures.of("resetPrice", "수동 가격 지정과 자동 계산 되돌리기는 함께 보낼 수 없습니다.");
            }
        }
        Product product = requireEditable(productId);   // 판매 상태도 사전예약 오픈 3분 전부터는 못 바꾼다
        ProductOption option = options.findById(variantId)
                .filter(o -> o.getProductId().equals(productId))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (request.price() != null) {
            option.overridePrice(request.price());
        }
        if (request.resets()) {
            List<Long> valueIds = selectionsByOption(productId).getOrDefault(variantId, List.of());
            Map<Long, ProductOptionValue> valueById = valuesOf(productId);
            option.resetToComputed(computedPrice(product, valueIds.stream().map(valueById::get).toList()));
        }
        if (request.status() != null) {
            option.changeStatus(request.status());
        }
        options.flush();
        ProductDetailView.Variant edited = variantOf(productId, variantId);
        requireNotOpenedAtCommit(product);
        return edited;
    }

    /**
     * 상품 판매 시작 · 중지(ACTIVE ↔ PAUSED). 같은 상태면 바꾸지 않고 그대로 답한다. 사전예약은 다른 수정처럼 오픈 3분 전부터 409 다
     * (2026-10-04 결정 — preorder 의 접수용 상품 사본이 1분마다 갱신돼 오픈 뒤 전환은 접수에 늦게 닿는다). 오픈 전에 PAUSED 로 둔 채
     * 오픈을 넘긴 상품은 그대로 판매 중지다 — 회차 취소로 보지 않고 이벤트도 없다(같은 날 결정).
     *
     * <p>사전예약 <b>오픈 뒤</b>(회차 opens_at ≤ 지금)의 PAUSED 는 회차 취소다 — {@link #cancelCampaign}. 사유는 그때만 받는다.
     */
    @Transactional
    public SaleStatusView changeSaleStatus(Long productId, SaleStatusChangeRequest request) {
        Product product = lockProduct(productId);
        String reason = request.reason() == null ? null : request.reason().strip();
        if (product.getSaleMode() == SaleMode.PREORDER && opened(product)) {
            return cancelCampaign(product, request.status(), reason);
        }
        if (reason != null) {
            throw ValidationFailures.of("reason", "사유는 사전예약 오픈 뒤 판매 중지(회차 취소)에만 받습니다.");
        }
        requireNotOpened(product, STATUS_FROZEN);
        product.changeStatus(request.status());
        products.flush();
        requireNotOpened(product, STATUS_FROZEN);   // 커밋 직전 — 잠금 대기 중에 오픈 3분 전을 넘겼을 수 있다
        return new SaleStatusView(productId, product.getStatus(), false);
    }

    /**
     * 사전예약 오픈 뒤 판매 중지 = 회차 취소. 판매 중지로 두고 취소 시각을 남기고, 같은 트랜잭션에서 PREORDER_CAMPAIGN_CANCELED 를 아웃박스에 적는다.
     * preorder 가 받아 회차를 지금 마감하고 진행 중 예약의 취소를 시작한다. 되돌릴 수 없다 — 오픈 뒤 ACTIVE 는 409.
     * 이미 취소된 상품에 다시 보내면 이벤트를 다시 적지 않고 같은 답(접수됨)을 준다. 오픈 판정은 상품 행 잠금 아래에서 했다 — 같은 상품의 취소는 줄 서므로
     * 이벤트는 한 번만 적힌다.
     */
    private SaleStatusView cancelCampaign(Product product, SaleStatus requested, String reason) {
        if (requested != SaleStatus.PAUSED) {
            throw new BusinessException(CatalogErrorCode.STATE_CONFLICT, "사전예약 오픈 뒤에는 판매를 다시 시작할 수 없습니다.");
        }
        if (reason == null || reason.isEmpty()) {
            throw ValidationFailures.of("reason", "오픈 뒤 판매 중지는 회차 취소라 사유가 필요합니다.");
        }
        if (product.cancelCampaign(clock.instant())) {
            products.flush();
            outbox.append(new PreorderCampaignCanceled(product.getId(), reason));
        }
        return new SaleStatusView(product.getId(), product.getStatus(), true);
    }

    /** 사전예약 회차가 열렸는가(opens_at ≤ 지금). 회차가 없으면(등록 이벤트 처리 전) 열리지 않았다. */
    private boolean opened(Product product) {
        return crossReads.findCampaign(product.getId())
                .map(window -> !clock.instant().isBefore(window.opensAt()))
                .orElse(false);
    }

    /** 공개 ↔ 비공개. 언제든 바꾼다 — 오픈 판정을 하지 않는다. 다른 수정과 줄 서도록 상품 행은 잠근다. */
    @Transactional
    public VisibilityView changeVisibility(Long productId, VisibilityChangeRequest request) {
        Product product = lockProduct(productId);
        if (request.visible()) {
            product.publish();
        } else {
            product.hide();
        }
        products.flush();
        return new VisibilityView(productId, product.isVisible());
    }

    /**
     * 상품 행을 잠그고 읽는다 — 한 상품에 대한 수정을 줄 세운다. 이 뒤의 읽기(기본가 · 추가금 · 선택)는 앞 수정이 커밋한 값을 본다.
     * 잠그지 않으면 겹친 두 수정이 서로의 커밋 전 값으로 옵션 가격을 계산해 덮는다(ProductRepository#findForUpdate).
     */
    private Product lockProduct(Long productId) {
        return products.findForUpdate(productId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    /** 상품이 있고 지금 고칠 수 있는 상태인가 — 사전예약 오픈 뒤면 409. */
    private Product requireEditable(Long productId) {
        Product product = lockProduct(productId);
        requireNotOpened(product);
        return product;
    }

    private void requireNotOpened(Product product) {
        requireNotOpened(product, EDIT_FROZEN);
    }

    private void requireNotOpened(Product product, String message) {
        if (product.getSaleMode() != SaleMode.PREORDER) {
            return;
        }
        boolean frozen = crossReads.findCampaign(product.getId())
                .map(window -> !clock.instant().isBefore(window.opensAt().minus(FREEZE_BEFORE_OPEN)))
                .orElse(false);
        if (frozen) {
            throw new BusinessException(CatalogErrorCode.STATE_CONFLICT, message);
        }
    }

    /**
     * 커밋 직전에 다시 판정한다. 시작 때만 보면 그 뒤 오픈 시각이 지나거나(잠금 대기 · 재계산에 걸린 시간) preorder 가 회차 시각을 앞당겨도
     * 오픈 뒤에 커밋된다. 이 조회는 READ COMMITTED 라 그때까지 커밋된 회차를 본다. 남는 틈은 이 판정과 커밋 사이뿐이다.
     */
    private void requireNotOpenedAtCommit(Product product) {
        requireNotOpened(product);
    }

    /** 수동 가격이 아닌 옵션을 `기본가 + Σ추가금` 으로. valueId 를 주면 그 값을 고른 옵션만, null 이면 전부. */
    private void recomputePrices(Product product, Long valueId) {
        Map<Long, List<Long>> valueIdsByOption = selectionsByOption(product.getId());
        Map<Long, ProductOptionValue> valueById = valuesOf(product.getId());
        for (ProductOption option : options.findByProductIdOrderById(product.getId())) {
            List<Long> valueIds = valueIdsByOption.getOrDefault(option.getId(), List.of());
            if (valueId != null && !valueIds.contains(valueId)) {
                continue;
            }
            BigDecimal computed = product.getBasePrice();
            for (Long id : valueIds) {
                computed = computed.add(valueById.get(id).getSurcharge());
            }
            option.recomputePrice(computed);
        }
    }

    /**
     * 값 이름 수정 — 오타 · 표시 문구 모두(설계 §2.1 "옵션 변경"). 같은 축에 같다고 보는 값(대소문자 · 악센트 · 전각)이 있으면 400, 용량은 형식을
     * 지켜야 한다. 이름을 복사해 둔 곳을 같은 트랜잭션에서 고친다: 그 값을 고른 옵션의 표시명 · 필터 속성 · 표시 속성, 색상이면 사진 묶음 키.
     * 이미 접수된 예약 · 주문은 자기 스냅샷을 가지므로 바뀌지 않는다. 뜻이 바뀌는 수정(블랙 → 화이트)도 막지 않는다 — 관리자의 판단이다.
     */
    private void renameValue(Product product, String axisKey, ProductOptionValue value, String raw) {
        String normalized = normalized(axisKey, raw, "value");
        if (ProductOptionAxis.STORAGE.equals(axisKey) && !ProductRegistrationValidator.STORAGE.matcher(normalized).matches()) {
            throw ValidationFailures.of("value", "용량은 숫자 + MB/GB/TB 로 적습니다.");
        }
        String key = ProductRegistrationValidator.collationKey(normalized);
        boolean taken = values.findByAxisIdInOrderByAxisIdAscPositionAsc(List.of(value.getAxisId())).stream()
                .filter(other -> !other.getId().equals(value.getId()))
                .anyMatch(other -> ProductRegistrationValidator.collationKey(other.getNormalizedValue()).equals(key));
        if (taken) {
            throw ValidationFailures.of("value", "같은 값이 이미 있습니다.");
        }
        String before = value.getNormalizedValue();
        value.rename(raw, normalized);
        try {
            values.flush();
        } catch (DataIntegrityViolationException e) {
            // 콜레이션 흉내가 못 잡는 같은 값(ß = ss …)은 DB UNIQUE 가 최종 판정한다
            if (ConstraintViolations.mentionsKey(e, "uq_option_value")) {
                throw ValidationFailures.of("value", "같은 값이 이미 있습니다.");
            }
            throw e;
        }
        if (ProductOptionAxis.COLOR.equals(axisKey) && !before.equals(normalized)) {
            int moved = images.renameGalleryBundle(product.getId(), before, normalized);
            // 조건부 UPDATE 의 결과를 본다. 0 행은 그 색상에 사진이 없을 때만 정상이다 — 옛 키로 남은 사진이 있으면 이름만 바뀌고 사진이
            // 어느 색상에도 안 붙은 채 커밋되므로 트랜잭션을 실패시킨다. 상품 행 잠금 안이라 새 사진이 끼어들 수 없다
            if (moved == 0 && images.countByProductIdAndKindAndBundleKey(product.getId(), ImageKind.GALLERY, before) > 0) {
                throw new IllegalStateException("gallery bundle " + before + " was not moved to " + normalized);
            }
        }
        reattributeOptionsUsing(product, value.getId());
    }

    /** 그 값을 고른 옵션의 표시명 · 필터 속성 · 표시 속성을 축 순서의 조합에서 다시 만든다. 표시명이 상한을 넘으면 400(DB 1406 → 500 이 되지 않게). */
    private void reattributeOptionsUsing(Product product, Long valueId) {
        Map<Long, List<Long>> valueIdsByOption = selectionsByOption(product.getId());
        Map<Long, ProductOptionValue> valueById = valuesOf(product.getId());
        Map<Long, ProductOptionAxis> axisById = axesOf(product.getId());
        for (ProductOption option : options.findByProductIdOrderById(product.getId())) {
            List<Long> valueIds = valueIdsByOption.getOrDefault(option.getId(), List.of());
            if (!valueIds.contains(valueId)) {
                continue;
            }
            List<Pick> picks = new ArrayList<>(valueIds.stream()
                    .map(id -> new Pick(axisById.get(valueById.get(id).getAxisId()), valueById.get(id))).toList());
            picks.sort(Comparator.comparingInt(pick -> pick.axis().getPosition()));
            OptionCombination combination = OptionCombination.of(product.getId(), picks);
            if (combination.title().length() > ProductRegistrationValidator.MAX_OPTION_TITLE_LENGTH) {
                throw ValidationFailures.of("value",
                        "옵션 표시명이 %d자를 넘습니다: %s".formatted(ProductRegistrationValidator.MAX_OPTION_TITLE_LENGTH, combination.title()));
            }
            option.reattribute(combination);
        }
    }

    /** 선택이 없는 옵션(축 없는 상품)의 표시명은 상품 제목이다 — 제목이 바뀌면 따라간다. 제목은 100자라 표시명 상한(120)을 넘지 않는다. */
    private void retitleStandaloneOptions(Product product) {
        Map<Long, List<Long>> valueIdsByOption = selectionsByOption(product.getId());
        for (ProductOption option : options.findByProductIdOrderById(product.getId())) {
            if (valueIdsByOption.getOrDefault(option.getId(), List.of()).isEmpty()) {
                option.retitle(OptionCombination.titleOf(List.of(), product.getTitle()));
            }
        }
    }

    /** 저장 규칙의 정규화. 비었으면(공백뿐 · 전각 공백 포함) 그 칸의 400 — 정규화가 던지는 IllegalArgumentException 이 500 으로 새지 않게. */
    private static String normalized(String axisKey, String raw, String field) {
        try {
            return ProductOptionValue.normalizeFor(axisKey, raw);
        } catch (IllegalArgumentException e) {
            throw ValidationFailures.of(field, "값이 비었습니다.");
        }
    }

    private BigDecimal computedPrice(Product product, List<ProductOptionValue> picked) {
        BigDecimal price = product.getBasePrice();
        for (ProductOptionValue value : picked) {
            price = price.add(value.getSurcharge());
        }
        return price;
    }

    private static String defaultSku(List<ProductOptionValue> picked) {
        if (picked.isEmpty()) {
            return ProductRegistrationValidator.STANDALONE_SKU;
        }
        return String.join("-", picked.stream().map(ProductOptionValue::getNormalizedValue).toList());
    }

    private Map<Long, ProductOptionAxis> axesOf(Long productId) {
        Map<Long, ProductOptionAxis> byId = new LinkedHashMap<>();
        axes.findByProductIdOrderByPosition(productId).forEach(a -> byId.put(a.getId(), a));
        return byId;
    }

    private Map<Long, ProductOptionValue> valuesOf(Long productId) {
        Map<Long, ProductOptionValue> byId = new LinkedHashMap<>();
        values.findByAxisIdInOrderByAxisIdAscPositionAsc(axesOf(productId).keySet()).forEach(v -> byId.put(v.getId(), v));
        return byId;
    }

    private Map<Long, List<Long>> selectionsByOption(Long productId) {
        Map<Long, List<Long>> byOption = new LinkedHashMap<>();
        for (ProductOptionSelection selection : selections.findByProductId(productId)) {
            byOption.computeIfAbsent(selection.getId().getOptionId(), id -> new ArrayList<>()).add(selection.getValueId());
        }
        return byOption;
    }

    private ProductDetailView.Variant variantOf(Long productId, Long variantId) {
        return detailService.findAdminProduct(productId).product().variants().stream()
                .filter(v -> v.variantId().equals(variantId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("variant " + variantId + " vanished"));
    }
}
