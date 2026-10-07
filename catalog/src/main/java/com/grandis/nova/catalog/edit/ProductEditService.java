package com.grandis.nova.catalog.edit;

import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.catalog.detail.AdminProductDetail;
import com.grandis.nova.catalog.detail.ProductDetailService;
import com.grandis.nova.catalog.detail.ProductDetailView;
import com.grandis.nova.catalog.listing.ProductListingQueryRepository;
import com.grandis.nova.catalog.option.CollationDuplicates;
import com.grandis.nova.catalog.option.OptionCombination;
import com.grandis.nova.catalog.option.OptionText;
import com.grandis.nova.catalog.option.ProductOptions;
import com.grandis.nova.catalog.option.ProductOptions.Pick;
import com.grandis.nova.catalog.product.Amounts;
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
 * <p><b>사전예약은 오픈 3분 전부터 공개 여부 말고는 아무것도 못 바꾼다.</b> 표시 정보 · 가격 · 추가금 · 값 · 조합 추가 · 옵션 판매 상태 · 상품 판매 상태 전부
 * 409 STATE_CONFLICT(2026-09-29 결정 — 판매 상태도 막는다. preorder 는 접수용 상품 사본을 캐시해 두고 그 값으로 판정한다. 사전예약 상품을
 * 고치면 PREORDER_PRODUCT_CHANGED 로 알려 preorder 가 모든 인스턴스의 사본을 비우지만, 비우기는 아웃박스 릴레이 · SQS 전달만큼 늦고
 * preorder 의 인스턴스 간 알림이 실패하면 사본은 최대 30분(캐시 만료) 묵을 수 있다 — contracts/preorder-internal.md "캐시". 그래서 오픈
 * 직전 · 뒤의 변경은 접수에 늦게 닿을 수 있어 3분 전부터 막는다). 판정은 preorder 의 회차(opens_at − 3분 ≤ 지금)로 하고, 회차가 없으면
 * (preorder 가 등록 이벤트를 처리하기 전) 아직 잠기지 않았다.
 *
 * <p><b>재계산.</b> 옵션 가격은 늘 `기본가 + Σ추가금` 이다. 기본 가격이 바뀌면 모든 옵션을, 추가금이 바뀌면 그 값을 고른 옵션을 다시 계산한다.
 * 옵션 가격을 직접 고치는 경로는 없다(2026-10-06 결정 — 조합 하나만 다른 가격은 받지 않는다).
 *
 * <p><b>구성은 바꾸지 않는다.</b> 옵션의 조합 · sku 는 불변이다(값 이름 수정은 정규화값도 바꾸지만 조합은 값 id 로 묶여 그대로다). 실제 색상 · 용량 구성이 바뀌면 값을 더하고 새 조합을 만들고 옛 옵션을
 * 판매 중지한다. 옛 옵션에 주문 이력이 있는지는 catalog 가 볼 수 없다(주문 표를 읽지 않는다) — 그래서 지우지 않고 상태로 숨긴다.
 *
 * <p><b>한 상품의 수정은 줄 선다.</b> 수정은 모두 상품 행을 잠그고(SELECT … FOR UPDATE) 시작한다. 오픈 판정은 시작 때 한 번, 커밋
 * 직전에 한 번 더 한다(잠금 대기 · 재계산 중에 오픈 시각이 지날 수 있다). 회차 취소 갈래는 이미 오픈 뒤라 다시 보지 않는다.
 * 회차가 취소된 상품(campaign_canceled_at)은 공개 여부 말고는 아무것도 못 바꾼다 — 회차 시각과 상관없이 409.
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
    /**
     * 회차 취소 사유의 최대 길이 — 앞뒤 공백을 뺀 Java 문자열 길이(UTF-16 단위). preorder 는 받은 사유를 같은 단위 500 으로 잘라 이력에 남기므로
     * 이 길이를 넘지 않게 받으면 preorder 가 자를 일이 없다(코드 포인트로 세면 이모지가 섞인 사유를 preorder 가 서로게이트 쌍 가운데서 자를 수 있다).
     */
    private static final int REASON_MAX_LENGTH = 500;

    private final ProductRepository products;
    private final ProductOptionRepository options;
    private final ProductListingQueryRepository crossReads;
    private final CollationDuplicates duplicates;
    private final ProductDetailService detailService;
    private final OutboxWriter outbox;
    private final Clock clock;

    public ProductEditService(ProductRepository products, ProductOptionRepository options,
                              ProductListingQueryRepository crossReads, CollationDuplicates duplicates,
                              ProductDetailService detailService, OutboxWriter outbox, Clock clock) {
        this.products = products;
        this.options = options;
        this.crossReads = crossReads;
        this.duplicates = duplicates;
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
            recomputePrices(product, product.getOptions(), null, "basePrice");
        }
        products.flush();
        AdminProductDetail edited = detailService.findAdminProduct(productId);
        requireNotOpenedAtCommit(product);
        notifyPreorder(product);
        return edited;
    }

    @Transactional
    public AdminProductDetail addOptionValue(Long productId, OptionValueAddRequest request) {
        BigDecimal surcharge = request.surcharge() == null ? BigDecimal.ZERO
                : ProductRegistrationValidator.requireWholeWon(request.surcharge(), "surcharge");
        Product product = requireEditable(productId);
        ProductOptions document = product.getOptions();
        String axisKey = request.axisKey().strip().toLowerCase(Locale.ROOT);
        ProductOptions.Axis axis = document.axis(axisKey)
                .orElseThrow(() -> ValidationFailures.of("axisKey", "이 상품에 없는 축입니다: " + request.axisKey()));
        String normalized = normalized(axisKey, request.value(), "value");
        if (OptionText.STORAGE.equals(axisKey) && !ProductRegistrationValidator.STORAGE.matcher(normalized).matches()) {
            throw ValidationFailures.of("value", "용량은 숫자 + MB/GB/TB 로 적습니다.");
        }
        requireStorableValue(request.value());
        requireUniqueValue(axis, null, normalized);
        String hex = ProductRegistrationValidator.hexOf(axisKey, request.hex(), "hex");
        List<ProductOptions.Value> values = new ArrayList<>(axis.values());
        values.add(new ProductOptions.Value(ProductOptions.newValueId(), OptionText.normalize(request.value()), normalized, hex,
                Amounts.requireWholeWon(surcharge, "surcharge"), List.of()));
        product.replaceOptions(document.withAxis(new ProductOptions.Axis(axis.key(), axis.label(), values)));
        products.flush();
        AdminProductDetail edited = detailService.findAdminProduct(product.getId());
        requireNotOpenedAtCommit(product);
        notifyPreorder(product);
        return edited;
    }

    @Transactional
    public AdminProductDetail editOptionValue(Long productId, String valueId, OptionValueEditRequest request) {
        if (request.isEmpty()) {
            throw ValidationFailures.of("body", "바꿀 칸이 하나도 없습니다.");
        }
        if (request.surcharge() != null) {
            ProductRegistrationValidator.requireWholeWon(request.surcharge(), "surcharge");
        }
        Product product = requireEditable(productId);
        ProductOptions document = product.getOptions();
        Pick located = document.pickOf(valueId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        ProductOptions.Axis axis = located.axis();
        ProductOptions.Value value = located.value();
        boolean renamed = false;
        if (request.value() != null) {
            String normalized = renamedValue(axis, value, request.value());
            renamed = true;
            value = new ProductOptions.Value(value.id(), OptionText.normalize(request.value()), normalized, value.hex(), value.surcharge(),
                    value.images());
        }
        if (request.hex() != null) {
            value = new ProductOptions.Value(value.id(), value.value(), value.normalized(),
                    ProductRegistrationValidator.hexOf(axis.key(), request.hex(), "hex"), value.surcharge(), value.images());
        }
        boolean repriced = request.surcharge() != null && value.surcharge().compareTo(request.surcharge()) != 0;
        if (repriced) {
            value = new ProductOptions.Value(value.id(), value.value(), value.normalized(), value.hex(),
                    Amounts.requireWholeWon(request.surcharge(), "surcharge"), value.images());
        }
        ProductOptions.Value edited = value;
        List<ProductOptions.Value> values = axis.values().stream().map(v -> v.id().equals(edited.id()) ? edited : v).toList();
        ProductOptions next = document.withAxis(new ProductOptions.Axis(axis.key(), axis.label(), values));
        product.replaceOptions(next);
        if (renamed) {
            reattributeOptionsUsing(product, next, valueId);
        }
        if (repriced) {
            recomputePrices(product, next, valueId, "surcharge");
        }
        products.flush();
        AdminProductDetail result = detailService.findAdminProduct(productId);
        requireNotOpenedAtCommit(product);
        notifyPreorder(product);
        return result;
    }

    @Transactional
    public ProductDetailView.Variant addVariant(Long productId, VariantAddRequest request) {
        Map<String, String> given = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : request.selections().entrySet()) {
            String axisKey = entry.getKey() == null ? "" : entry.getKey().strip().toLowerCase(Locale.ROOT);
            if (given.put(axisKey, entry.getValue()) != null) {
                // "Color" 와 "color" 가 같이 오면 뒤의 것이 조용히 이기지 않게
                throw ValidationFailures.of("selections", "같은 축이 두 번 왔습니다(키는 대소문자를 가리지 않습니다): " + axisKey);
            }
        }
        Product product = requireEditable(productId);
        ProductOptions document = product.getOptions();
        List<Pick> picks = new ArrayList<>();
        for (ProductOptions.Axis axis : document.axes()) {
            if (!given.containsKey(axis.key())) {
                throw ValidationFailures.of("selections", "축 " + axis.key() + " 의 값이 없습니다.");
            }
            String raw = given.remove(axis.key());
            String field = "selections." + axis.key();
            String normalized = normalized(axis.key(), raw, field);
            ProductOptions.Value value = axis.valueByNormalized(normalized)
                    .orElseThrow(() -> ValidationFailures.of(field, "축에 없는 값입니다: " + raw + " (값을 먼저 더하세요: POST …/option-values)"));
            picks.add(new Pick(axis, value));
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
        String sku = request.sku() == null || request.sku().isBlank() ? defaultSku(picks) : request.sku().strip();
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
        BigDecimal price = ProductRegistrationValidator.requireStorablePrice(computedPrice(product, picks), "selections");
        ProductOption option;
        try {
            option = options.saveAndFlush(ProductOption.of(sku, price, combination));
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
        ProductDetailView.Variant added = variantOf(productId, option.getId());
        requireNotOpenedAtCommit(product);
        notifyPreorder(product);
        return added;
    }

    @Transactional
    public ProductDetailView.Variant editVariant(Long productId, Long variantId, VariantEditRequest request) {
        if (request.isEmpty()) {
            throw ValidationFailures.of("body", "바꿀 칸이 하나도 없습니다.");
        }
        Product product = requireEditable(productId);   // 판매 상태도 사전예약 오픈 3분 전부터는 못 바꾼다
        ProductOption option = options.findById(variantId)
                .filter(o -> o.getProductId().equals(productId))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        option.changeStatus(request.status());
        options.flush();
        ProductDetailView.Variant edited = variantOf(productId, variantId);
        requireNotOpenedAtCommit(product);
        notifyPreorder(product);
        return edited;
    }

    /**
     * 상품 판매 시작 · 중지(ACTIVE ↔ PAUSED). 같은 상태면 바꾸지 않고 그대로 답한다. 사전예약은 다른 수정처럼 오픈 3분 전부터 409 다
     * (2026-10-04 결정 — 변경 알림이 preorder 접수에 닿기까지 늦을 수 있어 오픈 뒤 전환은 접수에 늦게 닿는다). 바뀌었으면 PREORDER_PRODUCT_CHANGED 를 적는다. 오픈 전에 PAUSED 로 둔 채
     * 오픈을 넘긴 상품은 그대로 판매 중지다 — 회차 취소로 보지 않고 이벤트도 없다(같은 날 결정).
     *
     * <p>사전예약 <b>오픈 뒤</b>(회차 opens_at ≤ 지금)의 PAUSED 는 회차 취소다 — {@link #cancelCampaign}. 사유는 그때만 받는다.
     */
    @Transactional
    public SaleStatusView changeSaleStatus(Long productId, SaleStatusChangeRequest request) {
        Product product = lockProduct(productId);
        // 빈 사유는 보내지 않은 것으로 본다 — 화면이 늘 칸을 채워 보내도 일반 전환이 막히지 않게. 회차 취소에서는 필수다
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().strip();
        // 이미 회차가 취소된 상품은 취소 갈래로만 — 취소 뒤 preorder 가 오픈을 미래로 옮겨도 되돌리기 · 일반 전환이 되지 않는다
        if (product.getSaleMode() == SaleMode.PREORDER && (product.getCampaignCanceledAt() != null || opened(product))) {
            return cancelCampaign(product, request.status(), reason);
        }
        requireNotOpened(product, STATUS_FROZEN);   // 잠금 판정이 먼저다 — 오픈 3분 전 구간에서는 사유가 와도 409
        if (reason != null) {
            throw ValidationFailures.of("reason", "사유는 사전예약 오픈 뒤 판매 중지(회차 취소)에만 받습니다.");
        }
        SaleStatus before = product.getStatus();
        product.changeStatus(request.status());
        products.flush();
        requireNotOpened(product, STATUS_FROZEN);   // 커밋 직전 — 잠금 대기 중에 오픈 3분 전을 넘겼을 수 있다
        if (product.getStatus() != before) {
            notifyPreorder(product);
        }
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
            throw new BusinessException(CatalogErrorCode.STATE_CONFLICT, "오픈했거나 회차가 취소된 사전예약은 판매를 다시 시작할 수 없습니다.");
        }
        if (reason == null) {
            throw ValidationFailures.of("reason", "오픈 뒤 판매 중지는 회차 취소라 사유가 필요합니다.");
        }
        if (reason.length() > REASON_MAX_LENGTH) {
            throw ValidationFailures.of("reason", "사유는 앞뒤 공백을 뺀 %d자 이하입니다.".formatted(REASON_MAX_LENGTH));
        }
        if (product.cancelCampaign(clock.instant())) {
            products.flush();   // 표식 UPDATE 를 먼저 내려 제약(CHECK) 위반이 이벤트를 적기 전에 이 자리에서 드러나게 한다
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

    /** 공개 ↔ 비공개. 언제든 바꾼다 — 오픈 판정을 하지 않는다. 다른 수정과 줄 서도록 상품 행은 잠근다. 바뀌었으면 PREORDER_PRODUCT_CHANGED 를 적는다. */
    @Transactional
    public VisibilityView changeVisibility(Long productId, VisibilityChangeRequest request) {
        Product product = lockProduct(productId);
        boolean before = product.isVisible();
        if (request.visible()) {
            product.publish();
        } else {
            product.hide();
        }
        products.flush();
        if (product.isVisible() != before) {
            notifyPreorder(product);
        }
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
        if (product.getCampaignCanceledAt() != null) {
            // 회차가 취소된 상품은 더 바꿀 것이 없다 — 회차 시각과 상관없이(취소 뒤 오픈이 미래로 옮겨져도) 409
            throw new BusinessException(CatalogErrorCode.STATE_CONFLICT, "회차가 취소된 상품은 바꿀 수 없습니다.");
        }
        boolean frozen = crossReads.findCampaign(product.getId())
                .map(window -> !clock.instant().isBefore(window.opensAt().minus(FREEZE_BEFORE_OPEN)))
                .orElse(false);
        if (frozen) {
            throw new BusinessException(CatalogErrorCode.STATE_CONFLICT, message);
        }
    }

    /**
     * 사전예약 상품이면 PREORDER_PRODUCT_CHANGED 를 같은 트랜잭션의 아웃박스에 적는다 — preorder 가 모든 인스턴스의 접수용 상품 사본(캐시)을 비워
     * 다음 접수가 바뀐 값으로 판정하게 한다. 수정이 되돌려지면 이벤트도 남지 않는다. 일반 상품은 preorder 가 읽지 않으므로 적지 않는다.
     * 회차 취소는 자기 이벤트(PREORDER_CAMPAIGN_CANCELED)로 preorder 가 비우므로 여기를 거치지 않는다.
     */
    private void notifyPreorder(Product product) {
        if (product.getSaleMode() == SaleMode.PREORDER) {
            outbox.append(new PreorderProductChanged(product.getId()));
        }
    }

    /**
     * 커밋 직전에 다시 판정한다. 시작 때만 보면 그 뒤 오픈 시각이 지나거나(잠금 대기 · 재계산에 걸린 시간) preorder 가 회차 시각을 앞당겨도
     * 오픈 뒤에 커밋된다. 이 조회는 READ COMMITTED 라 그때까지 커밋된 회차를 본다. 남는 틈은 이 판정과 커밋 사이뿐이다.
     */
    private void requireNotOpenedAtCommit(Product product) {
        requireNotOpened(product);
    }

    /** 옵션 가격을 `기본가 + Σ추가금` 으로 다시 계산한다. valueId 를 주면 그 값을 고른 옵션만, null 이면 전부. */
    private void recomputePrices(Product product, ProductOptions document, String valueId, String cause) {
        for (ProductOption option : options.findByProductIdOrderById(product.getId())) {
            List<String> valueIds = OptionCombination.valueIdsOf(option.getCombinationKey());
            if (valueId != null && !valueIds.contains(valueId)) {
                continue;
            }
            option.recomputePrice(ProductRegistrationValidator.requireStorablePrice(
                    computedPrice(product, document.picksOf(valueIds)), cause));
        }
    }

    /**
     * 값 이름 수정 — 오타 · 표시 문구 모두. 같은 축에 같다고 보는 값(대소문자 · 악센트 · 전각 · 확장 문자)이 있으면 400, 용량은
     * 형식을 지켜야 한다. 사진은 그 값 아래에 있어 따라간다. 이름을 복사해 둔 옵션의 표시명 · 필터 속성은 호출자가 같은 트랜잭션에서 고친다.
     * 이미 접수된 예약 · 주문은 자기 스냅샷을 가지므로 바뀌지 않는다. 뜻이 바뀌는 수정(블랙 → 화이트)도 막지 않는다 — 관리자의 판단이다.
     *
     * @return 새 정규화값
     */
    private String renamedValue(ProductOptions.Axis axis, ProductOptions.Value value, String raw) {
        String normalized = normalized(axis.key(), raw, "value");
        if (OptionText.STORAGE.equals(axis.key()) && !ProductRegistrationValidator.STORAGE.matcher(normalized).matches()) {
            throw ValidationFailures.of("value", "용량은 숫자 + MB/GB/TB 로 적습니다.");
        }
        requireStorableValue(raw);
        requireUniqueValue(axis, value.id(), normalized);
        return normalized;
    }

    /**
     * 같은 축에 같다고 보는 값이 없는가 — 앱의 콜레이션 흉내로 먼저 거르고, 흉내가 못 잡는 확장 문자(ß = ss …)는 같은 콜레이션의 DB 질의로 판정한다.
     *
     * @param exceptId 이름을 고치는 값 자신(새 값이면 null)
     */
    private void requireUniqueValue(ProductOptions.Axis axis, String exceptId, String normalized) {
        String key = ProductRegistrationValidator.collationKey(normalized);
        List<String> others = axis.values().stream().filter(v -> !v.id().equals(exceptId)).map(ProductOptions.Value::normalized).toList();
        if (others.stream().anyMatch(other -> ProductRegistrationValidator.collationKey(other).equals(key))) {
            throw ValidationFailures.of("value", "같은 값이 이미 있습니다.");
        }
        List<String> all = new ArrayList<>(others);
        all.add(normalized);
        if (duplicates.any(all)) {
            throw ValidationFailures.of("value", "같은 값이 이미 있습니다.");
        }
    }

    /** 그 값을 고른 옵션의 표시명 · 필터 속성을 축 순서의 조합에서 다시 만든다. 표시명이 상한을 넘으면 400(DB 1406 → 500 이 되지 않게). */
    private void reattributeOptionsUsing(Product product, ProductOptions document, String valueId) {
        for (ProductOption option : options.findByProductIdOrderById(product.getId())) {
            List<String> valueIds = OptionCombination.valueIdsOf(option.getCombinationKey());
            if (!valueIds.contains(valueId)) {
                continue;
            }
            OptionCombination combination = OptionCombination.of(product.getId(), document.picksOf(valueIds));
            if (combination.title().length() > ProductRegistrationValidator.MAX_OPTION_TITLE_LENGTH) {
                throw ValidationFailures.of("value",
                        "옵션 표시명이 %d자를 넘습니다: %s".formatted(ProductRegistrationValidator.MAX_OPTION_TITLE_LENGTH, combination.title()));
            }
            option.reattribute(combination);
        }
    }

    /**
     * 선택이 없는 옵션(축 없는 상품)의 표시명은 상품 제목의 정규화값이다 — 제목이 바뀌면 따라간다. 제목은 100자지만 NFC 가 글자를 늘릴 수
     * 있어(U+0958 한 글자 → 두 글자) 표시명 상한(120)을 넘으면 title 의 400 이다(등록과 같은 판정).
     */
    private void retitleStandaloneOptions(Product product) {
        for (ProductOption option : options.findByProductIdOrderById(product.getId())) {
            if (OptionCombination.valueIdsOf(option.getCombinationKey()).isEmpty()) {
                String title = OptionCombination.titleOf(List.of(), product.getTitle());
                if (title.length() > ProductRegistrationValidator.MAX_OPTION_TITLE_LENGTH) {
                    throw ValidationFailures.of("title", "옵션 표시명이 %d자를 넘습니다: %s".formatted(ProductRegistrationValidator.MAX_OPTION_TITLE_LENGTH, title));
                }
                option.retitle(title);
            }
        }
    }

    /** 저장 규칙의 정규화. 비었으면(공백뿐 · 전각 공백 포함) 그 칸의 400 — 정규화가 던지는 IllegalArgumentException 이 500 으로 새지 않게. */
    private static String normalized(String axisKey, String raw, String field) {
        try {
            return OptionText.normalizeFor(axisKey, raw);
        } catch (IllegalArgumentException e) {
            throw ValidationFailures.of(field, "값이 비었습니다.");
        }
    }

    /**
     * 저장하는 값(값 추가 · 이름 수정)의 정규화한 표시값이 칸(60자)에 담기는가 — NFC 가 글자를 늘린다. 형식 검사 뒤에 부른다: 비교 키가 표시값보다
     * 길어지는 것은 용량을 대문자로 접을 때뿐인데(ß → SS) 그런 값은 형식 검사(숫자 + MB/GB/TB)에서 이미 400 이다. 조회에만 쓰는 선택 값에는 걸지 않는다.
     */
    private static void requireStorableValue(String raw) {
        ProductRegistrationValidator.requireStorableText(OptionText.normalize(raw), ProductRegistrationValidator.MAX_OPTION_VALUE_LENGTH, "value");
    }

    private static BigDecimal computedPrice(Product product, List<Pick> picks) {
        BigDecimal price = product.getBasePrice();
        for (Pick pick : picks) {
            price = price.add(pick.value().surcharge());
        }
        return price;
    }

    private static String defaultSku(List<Pick> picks) {
        if (picks.isEmpty()) {
            return ProductRegistrationValidator.STANDALONE_SKU;
        }
        return String.join("-", picks.stream().map(pick -> pick.value().normalized()).toList());
    }

    private ProductDetailView.Variant variantOf(Long productId, Long variantId) {
        return detailService.findAdminProduct(productId).product().variants().stream()
                .filter(v -> v.variantId().equals(variantId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("variant " + variantId + " vanished"));
    }
}
