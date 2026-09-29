package com.grandis.nova.catalog.edit;

import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.catalog.detail.AdminProductDetail;
import com.grandis.nova.catalog.detail.ProductDetailService;
import com.grandis.nova.catalog.detail.ProductDetailView;
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
import com.grandis.nova.catalog.registration.ProductRegistrationValidator;
import com.grandis.nova.catalog.web.ConstraintViolations;
import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
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
 * 관리자 상품 수정 — 표시 정보 · 기본 가격 · 보증, 옵션 값 추가 · 수정, 옵션(조합) 추가 · 수정. 설계 §2.1 · §2.1.1.
 *
 * <p><b>사전예약 오픈 뒤에는 기준정보를 못 바꾼다.</b> 표시 정보 · 가격 · 추가금 · 값 · 조합 추가 전부 409 STATE_CONFLICT. 옵션의 판매 중지 · 재개만
 * 열려 있다(기준정보가 아니라 운영 명령). 오픈 여부는 preorder 의 회차(opens_at ≤ 지금)로 판정하고, 회차가 없으면(등록 ② 전) 아직 오픈 전이다.
 *
 * <p><b>재계산.</b> 기본 가격 · 추가금이 바뀌면 그 값을 고른 옵션 중 수동 가격이 아닌 것만 `기본가 + Σ추가금` 으로 다시 계산한다. 관리자가 직접 고친
 * 가격(priceOverridden)은 그대로 둔다.
 *
 * <p><b>구성은 바꾸지 않는다.</b> 값의 정규화값 · 옵션의 조합 · sku 는 불변이다. 실제 색상 · 용량 구성이 바뀌면 값을 더하고 새 조합을 만들고 옛 옵션을
 * 판매 중지한다. 옛 옵션에 주문 이력이 있는지는 catalog 가 볼 수 없다(주문 표를 읽지 않는다) — 그래서 지우지 않고 상태로 숨긴다.
 *
 * <p><b>한 상품의 수정은 줄 선다.</b> 다섯 수정 모두 상품 행을 잠그고(SELECT … FOR UPDATE) 시작한다. 오픈 판정은 시작 때 한 번, 커밋
 * 직전에 한 번 더 한다(잠금 대기 · 재계산 중에 오픈 시각이 지날 수 있다).
 *
 * <p>일반 상품의 새 옵션 재고는 여기서 받지 않는다 — 재고는 order 의 `PUT /admin/products/{id}/stock` 이 정본이다. 재고 행이 없는 동안 그 옵션은 품절로 보인다.
 */
@Service
public class ProductEditService {

    private final ProductRepository products;
    private final ProductOptionAxisRepository axes;
    private final ProductOptionValueRepository values;
    private final ProductOptionRepository options;
    private final ProductOptionSelectionRepository selections;
    private final ProductListingQueryRepository crossReads;
    private final ProductDetailService detailService;
    private final Clock clock;

    public ProductEditService(ProductRepository products, ProductOptionAxisRepository axes, ProductOptionValueRepository values,
                              ProductOptionRepository options, ProductOptionSelectionRepository selections,
                              ProductListingQueryRepository crossReads, ProductDetailService detailService, Clock clock) {
        this.products = products;
        this.axes = axes;
        this.values = values;
        this.options = options;
        this.selections = selections;
        this.crossReads = crossReads;
        this.detailService = detailService;
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
            retitleOptions(product, null);   // 축 없는 상품의 옵션 표시명은 상품 제목이다
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
            if (!normalized(axisKey, request.value(), "value").equals(value.getNormalizedValue())) {
                throw ValidationFailures.of("value", "표시 문구만 바꿀 수 있습니다(정규화값이 같아야 합니다). 구성이 바뀌면 값을 새로 더하고 옛 옵션을 판매 중지하세요.");
            }
            value.rename(request.value());
            retitleOptions(product, value.getId());
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
        }
        Product product = lockProduct(productId);
        ProductOption option = options.findById(variantId)
                .filter(o -> o.getProductId().equals(productId))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (request.price() != null) {
            requireNotOpened(product);   // 가격은 기준정보 — 사전예약 오픈 뒤 금지
            option.overridePrice(request.price());
        }
        if (request.status() != null) {
            option.changeStatus(request.status());   // 판매 중지 · 재개는 오픈 뒤에도 된다
        }
        options.flush();
        ProductDetailView.Variant edited = variantOf(productId, variantId);
        if (request.price() != null) {
            requireNotOpenedAtCommit(product);
        }
        return edited;
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
        if (product.getSaleMode() != SaleMode.PREORDER) {
            return;
        }
        boolean opened = crossReads.findCampaign(product.getId())
                .map(window -> !clock.instant().isBefore(window.opensAt()))
                .orElse(false);
        if (opened) {
            throw new BusinessException(CatalogErrorCode.STATE_CONFLICT, "사전예약 오픈 뒤에는 상품 정보 · 옵션 · 가격을 바꿀 수 없습니다.");
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
     * 옵션 표시명을 다시 만든다. valueId 를 주면 그 값을 고른 옵션을 축 순서대로, null 이면 선택이 없는 옵션(축 없는 상품 — 표시명이 상품
     * 제목)을. 새 표시명이 길이 상한을 넘으면 400 — 넘긴 채 쓰면 DB 가 1406 으로 거절해 500 이 된다.
     */
    private void retitleOptions(Product product, Long valueId) {
        Map<Long, List<Long>> valueIdsByOption = selectionsByOption(product.getId());
        Map<Long, ProductOptionValue> valueById = valuesOf(product.getId());
        Map<Long, ProductOptionAxis> axisById = axesOf(product.getId());
        for (ProductOption option : options.findByProductIdOrderById(product.getId())) {
            List<Long> valueIds = valueIdsByOption.getOrDefault(option.getId(), List.of());
            if (valueId == null ? !valueIds.isEmpty() : !valueIds.contains(valueId)) {
                continue;
            }
            List<ProductOptionValue> ordered = new ArrayList<>(valueIds.stream().map(valueById::get).toList());
            ordered.sort(Comparator.comparingInt(v -> axisById.get(v.getAxisId()).getPosition()));
            String title = OptionCombination.titleOf(ordered.stream().map(ProductOptionValue::getValue).toList(), product.getTitle());
            if (title.length() > ProductRegistrationValidator.MAX_OPTION_TITLE_LENGTH) {
                throw ValidationFailures.of(valueId == null ? "title" : "value",
                        "옵션 표시명이 %d자를 넘습니다: %s".formatted(ProductRegistrationValidator.MAX_OPTION_TITLE_LENGTH, title));
            }
            option.retitle(title);
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
