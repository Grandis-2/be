package com.grandis.nova.catalog.registration;

import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.catalog.category.CategoryRepository;
import com.grandis.nova.catalog.detail.ProductDetailService;
import com.grandis.nova.catalog.detail.ProductDetailView;
import com.grandis.nova.catalog.listing.ProductListingQueryRepository;
import com.grandis.nova.catalog.option.CollationDuplicates;
import com.grandis.nova.catalog.option.OptionCombination;
import com.grandis.nova.catalog.option.OptionText;
import com.grandis.nova.catalog.option.ProductOptions;
import com.grandis.nova.catalog.option.ProductOptions.Pick;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.ProductOption;
import com.grandis.nova.catalog.product.ProductOptionRepository;
import com.grandis.nova.catalog.product.ProductRepository;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.registration.ProductRegistrationRequest.Image;
import com.grandis.nova.catalog.registration.ProductRegistrationValidator.Axis;
import com.grandis.nova.catalog.registration.ProductRegistrationValidator.Combo;
import com.grandis.nova.catalog.registration.ProductRegistrationValidator.Draft;
import com.grandis.nova.catalog.registration.ProductRegistrationValidator.GalleryDraft;
import com.grandis.nova.catalog.registration.ProductRegistrationValidator.Value;
import com.grandis.nova.catalog.web.ConstraintViolations;
import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.outbox.OutboxWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 관리자 등록 — catalog 트랜잭션 하나. 상품(관리자가 고른 공개 여부 · 옵션 문서 · 썸네일 · 멱등 키) · 조합(옵션 행)과
 * 판매 방식별 등록 이벤트(아웃박스)를 한 번에 저장한다. 사전예약은 preorder 가 회차 · 차수를, 일반은 order 가 초기 재고를
 * 이벤트를 받아 자기 표에 만든다(2026-10-02 이벤트 방식 전환). 그 행이 생기기 전에는 노출되지 않는다(판매 방식별 준비).
 *
 * 같은 Idempotency-Key 가 다시 오면 본문 내용은 대조하지 않고 첫 등록을 돌려준다 — 준비가 끝났으면 200, 아직이면 202. 응답이 유실된 클라이언트는
 * 같은 키로 다시 보내고 productId 를 받는다. 이벤트는 다시 적지 않는다 — 아웃박스가 보낼 때까지 다시 보낸다.
 * 새 키 둘이 동시에 오면 products.idempotency_key 의 UNIQUE 가 하나를 거절하고 그 트랜잭션(상품 · 이벤트 포함)은 통째로 돌아간다.
 */
@Service
public class ProductRegistrationService {

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final ProductOptionRepository options;
    private final ProductRegistrationValidator validator;
    private final CollationDuplicates duplicates;
    private final ProductDetailService detailService;
    private final ProductListingQueryRepository crossReads;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final Duration minOpenLead;

    public ProductRegistrationService(CategoryRepository categories, ProductRepository products, ProductOptionRepository options,
                                      ProductRegistrationValidator validator, CollationDuplicates duplicates,
                                      ProductDetailService detailService, ProductListingQueryRepository crossReads,
                                      OutboxWriter outbox, Clock clock,
                                      @org.springframework.beans.factory.annotation.Value("${catalog.registration.min-open-lead:PT30M}")
                                      Duration minOpenLead) {
        this.categories = categories;
        this.products = products;
        this.options = options;
        this.validator = validator;
        this.duplicates = duplicates;
        this.detailService = detailService;
        this.crossReads = crossReads;
        this.outbox = outbox;
        this.clock = clock;
        this.minOpenLead = minOpenLead;
    }

    /**
     * @param idempotencyKey 앞뒤를 트림해서 쓴다 — 칼럼(utf8mb4_bin, PAD SPACE)은 뒤 공백만 같게 보고 앞 공백은 다른 키로 보므로(MySQL 8.4.11 실측)
     *                       앱이 양쪽을 잘라 하나의 규칙으로 만든다
     * @return CREATED 면 등록 상태와 미리보기를, REPLAYED(준비 끝) · IN_PROGRESS(준비 전) 면 등록 상태만 담는다. 같은 키로 다시 오면 본문 내용은
     *         대조하지 않고 첫 등록의 상태를 돌려준다. 형식 검사(모르는 칸 · 필수 칸)는 요청 경계에서 먼저 돌고, 오픈 시각 · 카테고리 검사는
     *         재전송 판정 뒤에 돈다 — 응답을 잃고 늦게 다시 보내도 productId 를 받는다. 같은 키에 다른 본문은 프론트 버그일 때뿐이다(2026-09-30 결정)
     * @throws BusinessException REGISTRATION_IN_PROGRESS(같은 새 키가 동시에 와서 다른 요청이 먼저 저장했다)
     */
    @Transactional
    public RegistrationOutcome register(String idempotencyKey, ProductRegistrationRequest request) {
        String key = idempotencyKey == null ? "" : idempotencyKey.strip();
        if (key.isEmpty() || key.length() > 100) {
            throw ValidationFailures.of("Idempotency-Key", "1~100자여야 합니다.");
        }
        Optional<Product> existing = products.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            Product registered = existing.get();
            boolean ready = crossReads.isReady(registered.getId());
            RegistrationOutcome.Kind kind = ready ? RegistrationOutcome.Kind.REPLAYED : RegistrationOutcome.Kind.IN_PROGRESS;
            return new RegistrationOutcome(kind, RegistrationStatusView.of(registered, ready), null);
        }

        if (!categories.existsById(request.categoryId())) {
            throw ValidationFailures.of("categoryId", "없는 카테고리입니다.");
        }
        Instant now = clock.instant();
        Draft draft = validator.validate(request, now, minOpenLead);
        requireNoCollationDuplicates(draft);
        ProductOptions document = documentOf(draft);
        Product product = saveProduct(Product.register(key, request.categoryId(), request.saleMode(), request.title(),
                request.basePrice(), request.description(), request.tags(), request.visible(), request.warranty().offered(),
                request.warranty().surcharge(), document));
        Long productId = product.getId();

        List<InStockProductRegistered.Item> initialStock = new ArrayList<>();
        for (Combo combo : draft.combos()) {
            ProductOption option = options.saveAndFlush(ProductOption.of(combo.sku(), combo.price(),
                    toCombination(productId, request.title(), combo, document)));
            if (combo.stock() != null) {
                initialStock.add(new InStockProductRegistered.Item(option.getId(), combo.stock()));
            }
        }

        // 다른 서비스가 만들 값은 같은 트랜잭션의 아웃박스로 보낸다. 롤백되면 이벤트도 없다
        outbox.append(request.saleMode() == SaleMode.PREORDER
                ? PreorderProductRegistered.of(productId, request.campaign(), request.shipmentBatches())
                : new InStockProductRegistered(productId, initialStock));
        // 미리보기는 커밋 전에 같은 트랜잭션에서 읽는다 — 커밋 전엔 남이 이 상품을 못 건드려 저장한 그대로가 실린다.
        // 준비는 아직이다: 이벤트는 커밋 뒤에 나가므로 회차 · 재고 행이 이 시점에 있을 수 없다
        ProductDetailView preview = detailService.findAdminProduct(productId).product();
        return new RegistrationOutcome(RegistrationOutcome.Kind.CREATED, RegistrationStatusView.of(product, false), preview);
    }

    @Transactional(readOnly = true)
    public RegistrationStatusView status(String idempotencyKey) {
        return products.findByIdempotencyKey(idempotencyKey == null ? "" : idempotencyKey.strip())
                .map(product -> RegistrationStatusView.of(product, crossReads.isReady(product.getId())))
                .orElseThrow(() -> new BusinessException(CatalogErrorCode.REGISTRATION_NOT_FOUND));
    }

    /**
     * 검증기의 콜레이션 흉내({@code collationKey})가 못 잡는 중복 — 확장 문자(ß=ss · Æ=AE · Œ=OE) — 은 같은 콜레이션의 DB 질의가 최종 판정한다.
     * 값은 축마다(칸은 그 축의 values), 상세 영역은 이름끼리(칸은 앞의 것과 겹친 첫 영역). 걸리면 그 칸의 400 이다.
     */
    private void requireNoCollationDuplicates(Draft draft) {
        for (int i = 0; i < draft.axes().size(); i++) {
            if (duplicates.any(draft.axes().get(i).values().stream().map(Value::normalized).toList())) {
                throw ValidationFailures.of("optionAxes[%d].values".formatted(i), "같은 값이 두 번 왔습니다.");
            }
        }
        List<String> sections = draft.detail().stream().map(ProductRegistrationValidator.DetailDraft::section).toList();
        if (duplicates.any(sections)) {
            // 칸은 앞의 것과 겹친 첫 영역 — 앱 1차 검사와 같은 자리를 가리킨다
            for (int i = 1; i < sections.size(); i++) {
                if (duplicates.any(sections.subList(0, i + 1))) {
                    throw ValidationFailures.of("images.detail[%d].section".formatted(i), "같은 영역이 두 번 왔습니다.");
                }
            }
        }
    }

    /** 검증을 지난 축 · 값 · 사진으로 옵션 문서를 만든다. 값 id 는 여기서 새로 만든다. 색상 사진은 그 색상 값 아래, 색상 없는 상품은 기본 묶음. */
    private static ProductOptions documentOf(Draft draft) {
        Map<String, List<ProductOptions.Image>> galleryByColor = draft.gallery().stream()
                .collect(Collectors.toMap(GalleryDraft::bundleKey, bundle -> images(bundle.items())));
        List<ProductOptions.Axis> axes = new ArrayList<>();
        for (Axis axis : draft.axes()) {
            boolean color = OptionText.COLOR.equals(axis.key());
            List<ProductOptions.Value> values = axis.values().stream()
                    .map(value -> new ProductOptions.Value(ProductOptions.newValueId(), value.display(), value.normalized(), value.hex(),
                            value.surcharge(), color ? galleryByColor.getOrDefault(value.normalized(), List.of()) : List.of()))
                    .toList();
            axes.add(new ProductOptions.Axis(axis.key(), axis.label(), values));
        }
        List<ProductOptions.Image> defaultImages = galleryByColor.getOrDefault("", List.of());
        List<ProductOptions.Section> detail = draft.detail().stream()
                .map(section -> new ProductOptions.Section(section.section(), images(section.items())))
                .toList();
        return new ProductOptions(axes, defaultImages, detail);
    }

    private static List<ProductOptions.Image> images(List<Image> items) {
        return items.stream().map(item -> new ProductOptions.Image(item.url(), Boolean.TRUE.equals(item.primary()))).toList();
    }

    private static OptionCombination toCombination(Long productId, String productTitle, Combo combo, ProductOptions document) {
        if (combo.selections().isEmpty()) {
            return OptionCombination.none(productId, productTitle);
        }
        List<Pick> picks = new ArrayList<>();
        for (ProductOptions.Axis axis : document.axes()) {
            ProductOptions.Value value = axis.valueByNormalized(combo.selections().get(axis.key()))
                    .orElseThrow(() -> new IllegalStateException("validated combination lost axis " + axis.key()));
            picks.add(new Pick(axis, value));
        }
        return OptionCombination.of(productId, picks);
    }

    private Product saveProduct(Product product) {
        try {
            return products.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            // 같은 새 키가 동시에 들어왔다 — 먼저 커밋한 쪽이 이긴다. 이 트랜잭션은 통째로 돌아간다
            if (ConstraintViolations.mentions(e, "uq_product_idempotency")) {
                throw new BusinessException(CatalogErrorCode.REGISTRATION_IN_PROGRESS);
            }
            throw e;
        }
    }
}
