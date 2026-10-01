package com.grandis.nova.catalog.registration;

import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.catalog.category.CategoryRepository;
import com.grandis.nova.catalog.detail.ProductDetailService;
import com.grandis.nova.catalog.detail.ProductDetailView;
import com.grandis.nova.catalog.image.ProductImage;
import com.grandis.nova.catalog.image.ProductImageRepository;
import com.grandis.nova.catalog.option.OptionCombination;
import com.grandis.nova.catalog.option.OptionCombination.Pick;
import com.grandis.nova.catalog.option.ProductOptionAxis;
import com.grandis.nova.catalog.option.ProductOptionAxisRepository;
import com.grandis.nova.catalog.option.ProductOptionSelectionRepository;
import com.grandis.nova.catalog.option.ProductOptionValue;
import com.grandis.nova.catalog.option.ProductOptionValueRepository;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.ProductOption;
import com.grandis.nova.catalog.product.ProductOptionRepository;
import com.grandis.nova.catalog.product.ProductRepository;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.registration.ProductRegistrationValidator.Axis;
import com.grandis.nova.catalog.registration.ProductRegistrationValidator.Combo;
import com.grandis.nova.catalog.registration.ProductRegistrationValidator.Draft;
import com.grandis.nova.catalog.registration.ProductRegistrationValidator.Value;
import com.grandis.nova.catalog.web.ConstraintViolations;
import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 관리자 등록의 ① — catalog 트랜잭션. 상품(비공개) · 축 · 값 · 조합 · 선택 · 사진 · 등록 기록을 한 번에 저장한다.
 * ②(preorder · order 호출)와 ③(완료 · 공개 전환)은 등록 조율 티켓이 {@link RegistrationOutcome#plan} 으로 이어 간다.
 *
 * 같은 Idempotency-Key 가 다시 오면 본문 내용은 대조하지 않고 첫 등록을 돌려준다 — 완료됐으면 같은 결과, 미완료면 재개 대상. 응답이 유실된 클라이언트는
 * 같은 키로 다시 보내고 productId 를 받는다. 새 키 둘이 동시에 오면 등록 기록의 UNIQUE 가 하나를 거절하고 그 트랜잭션은 통째로 돌아간다.
 */
@Service
public class ProductRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(ProductRegistrationService.class);

    /**
     * 등록 기록에 고정하는 계획의 직렬화기. 웹 설정의 매퍼를 쓰지 않는다 — 저장된 형식이 응답 설정에 따라 바뀌면 안 된다
     * ({@link com.grandis.nova.catalog.option.OptionCombination} 의 필터 JSON 과 같은 이유). 시각은 ISO 문자열, 날짜는 yyyy-MM-dd,
     * 옵션 id 키는 문자열로 쓰고 Long 으로 되읽는다(실측).
     * 엄격하게 읽는다 — 기본값으로는 모르는 칸을 무시하고 빠진 칸을 null 로 채워(실측) 칸 이름이 바뀐 저장본이 "회차 없음" 같은
     * 그럴듯한 계획으로 읽힌다. 두 기능을 켜면 둘 다 예외가 난다(실측).
     */
    private static final JsonMapper PLAN_JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .build();

    /** 저장된 계획이 없거나 읽을 수 없는 미완료 등록의 막힘 사유. 기록하지 않고 응답에만 싣는다 — 원인이 행에 있어 매번 같은 판정이 난다. */
    static final String PLAN_UNAVAILABLE = "PLAN_UNAVAILABLE";

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final ProductOptionAxisRepository axes;
    private final ProductOptionValueRepository values;
    private final ProductOptionRepository options;
    private final ProductOptionSelectionRepository selections;
    private final ProductImageRepository images;
    private final ProductRegistrationRepository registrations;
    private final ProductRegistrationValidator validator;
    private final ProductDetailService detailService;
    private final Clock clock;
    private final Duration minOpenLead;

    public ProductRegistrationService(CategoryRepository categories, ProductRepository products, ProductOptionAxisRepository axes,
                                      ProductOptionValueRepository values, ProductOptionRepository options,
                                      ProductOptionSelectionRepository selections, ProductImageRepository images,
                                      ProductRegistrationRepository registrations, ProductRegistrationValidator validator,
                                      ProductDetailService detailService, Clock clock,
                                      @org.springframework.beans.factory.annotation.Value("${catalog.registration.min-open-lead:PT30M}")
                                      Duration minOpenLead) {
        this.categories = categories;
        this.products = products;
        this.axes = axes;
        this.values = values;
        this.options = options;
        this.selections = selections;
        this.images = images;
        this.registrations = registrations;
        this.validator = validator;
        this.detailService = detailService;
        this.clock = clock;
        this.minOpenLead = minOpenLead;
    }

    /**
     * @param idempotencyKey 앞뒤를 트림해서 쓴다 — 칼럼(utf8mb4_bin, PAD SPACE)은 뒤 공백만 같게 보고 앞 공백은 다른 키로 보므로(MySQL 8.4.11 실측)
     *                       앱이 양쪽을 잘라 하나의 규칙으로 만든다
     * @return CREATED · IN_PROGRESS 면 ② 계획을 담는다 — 둘 다 ① 에서 등록 기록에 고정한 계획이다. REPLAYED 는 등록 상태(고정 필드)만 담는다. 같은 키로 다시 오면 본문 내용은
     *         대조하지 않고 첫 등록의 상태를 돌려준다. 형식 검사(모르는 칸 · 필수 칸)는 요청 경계에서 먼저 돌고, 오픈 시각 · 카테고리 검사는
     *         재전송 판정 뒤에 돈다 — 응답을 잃고 늦게 다시 보내도 productId 를 받는다. 같은 키에 다른 본문은 프론트 버그일 때뿐이다(2026-09-30 결정)
     * @throws BusinessException REGISTRATION_BLOCKED(자동 재개 불가 — 기록된 막힘 사유, 또는 저장된 계획이 없거나 못 읽을 때 PLAN_UNAVAILABLE) ·
     *                           REGISTRATION_IN_PROGRESS(동시 새 키)
     */
    @Transactional
    public RegistrationOutcome register(String idempotencyKey, ProductRegistrationRequest request) {
        String key = idempotencyKey == null ? "" : idempotencyKey.strip();
        if (key.isEmpty() || key.length() > 100) {
            throw ValidationFailures.of("Idempotency-Key", "1~100자여야 합니다.");
        }
        Optional<ProductRegistration> existing = registrations.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            ProductRegistration registration = existing.get();
            if (registration.isBlocked()) {
                throw blocked(registration.getProductId(), registration.getBlockedReason());
            }
            if (registration.isCompleted()) {
                return new RegistrationOutcome(RegistrationOutcome.Kind.REPLAYED, RegistrationStatusView.from(registration), null, null);
            }
            // 재개의 계획은 ① 에서 고정한 것이다. 이번 본문으로 다시 만들지 않는다. 저장본이 없거나 못 읽으면 재개 대상이 아니다 —
            // 202 로 "이어 갈 수 있다" 고 하면서 빈 계획을 넘기면 다음 단계가 "할 일 없음" 으로 읽는다
            RegistrationPlan plan = storedPlan(registration)
                    .orElseThrow(() -> blocked(registration.getProductId(), PLAN_UNAVAILABLE));
            return new RegistrationOutcome(RegistrationOutcome.Kind.IN_PROGRESS, RegistrationStatusView.from(registration), plan, null);
        }

        if (!categories.existsById(request.categoryId())) {
            throw ValidationFailures.of("categoryId", "없는 카테고리입니다.");
        }
        Instant now = clock.instant();
        Draft draft = validator.validate(request, now, minOpenLead);
        Product product = products.save(Product.register(request.categoryId(), request.saleMode(), request.title(),
                request.basePrice(), request.description(), request.tags(), request.warranty().offered(),
                request.warranty().surcharge()));
        Long productId = product.getId();

        Map<String, ProductOptionAxis> savedAxes = new LinkedHashMap<>();
        Map<String, Map<String, ProductOptionValue>> savedValues = new LinkedHashMap<>();
        for (int i = 0; i < draft.axes().size(); i++) {
            Axis axis = draft.axes().get(i);
            ProductOptionAxis savedAxis = axes.save(ProductOptionAxis.of(productId, axis.key(), axis.label(), i));
            savedAxes.put(axis.key(), savedAxis);
            Map<String, ProductOptionValue> byNormalized = new LinkedHashMap<>();
            saveOrReject(() -> {
                for (int j = 0; j < axis.values().size(); j++) {
                    Value value = axis.values().get(j);
                    byNormalized.put(value.normalized(), values.save(
                            ProductOptionValue.of(savedAxis.getId(), value.display(), value.normalized(), value.surcharge(), j)));
                }
                values.flush();
            }, "uq_option_value", "optionAxes[%d].values".formatted(i), "같은 값이 두 번 왔습니다.");
            savedValues.put(axis.key(), byNormalized);
        }

        Map<Long, Integer> stockByOptionId = new LinkedHashMap<>();
        for (Combo combo : draft.combos()) {
            OptionCombination combination = toCombination(productId, request.title(), combo, draft, savedAxes, savedValues);
            ProductOption option = saveOption(combo, combination);
            selections.saveAll(combination.selections(option.getId()));
            if (combo.stock() != null) {
                stockByOptionId.put(option.getId(), combo.stock());
            }
        }

        ProductOptionAxis colorAxis = savedAxes.get(ProductOptionAxis.COLOR);
        for (var bundle : draft.gallery()) {
            ProductOptionValue colorValue = bundle.bundleKey().isEmpty() ? null
                    : savedValues.get(ProductOptionAxis.COLOR).get(bundle.bundleKey());
            for (int position = 0; position < bundle.items().size(); position++) {
                var item = bundle.items().get(position);
                images.save(ProductImage.gallery(productId, colorAxis, colorValue, position, item.url(), item.primary()));
            }
        }
        for (int i = 0; i < draft.detail().size(); i++) {
            var section = draft.detail().get(i);
            saveOrReject(() -> {
                for (int position = 0; position < section.items().size(); position++) {
                    var item = section.items().get(position);
                    images.save(ProductImage.detail(productId, section.section(), position, item.url(), item.primary()));
                }
                images.flush();
            }, "uq_product_image_", "images.detail[%d].section".formatted(i), "같은 영역이 두 번 왔습니다.");
        }

        // 회차 시각은 마이크로초로 자른다 — preorder 는 datetime(6) 에 저장하므로, 그보다 잘게 고정하면 재개 때 저장된 회차와
        // 계획이 1마이크로초 미만 차이로 달라 보인다(직렬화기는 받은 나노초 9자리를 그대로 쓴다, 실측)
        RegistrationPlan plan = new RegistrationPlan(productId, stockByOptionId,
                request.campaign() == null ? null
                        : new RegistrationPlan.Campaign(request.campaign().opensAt().truncatedTo(ChronoUnit.MICROS),
                                request.campaign().closesAt().truncatedTo(ChronoUnit.MICROS)),
                request.shipmentBatches().stream().map(batch -> new RegistrationPlan.ShipmentBatch(batch.batchNumber(),
                        batch.positionFrom(), batch.positionTo(), batch.estimatedShipStart(), batch.estimatedShipEnd())).toList());
        ProductRegistration registration = saveRegistration(productId, key, request.visible(), PLAN_JSON.writeValueAsString(plan));
        // 미리보기는 커밋 전에 같은 트랜잭션에서 읽는다. 커밋 뒤 따로 읽으면 그 사이에 완료 · 공개 전환(③)이 끼어
        // completed=false · visible=true 라는 있은 적 없는 조합을 실을 수 있다. 커밋 전엔 남이 이 상품을 못 건드린다
        ProductDetailView preview = detailService.findAdminProduct(productId).product();
        return new RegistrationOutcome(RegistrationOutcome.Kind.CREATED, RegistrationStatusView.from(registration), plan, preview);
    }

    @Transactional(readOnly = true)
    public RegistrationStatusView status(String idempotencyKey) {
        return registrations.findByIdempotencyKey(idempotencyKey == null ? "" : idempotencyKey.strip())
                .map(RegistrationStatusView::from)
                .orElseThrow(() -> new BusinessException(CatalogErrorCode.REGISTRATION_NOT_FOUND));
    }

    private static OptionCombination toCombination(Long productId, String productTitle, Combo combo, Draft draft,
                                                   Map<String, ProductOptionAxis> savedAxes,
                                                   Map<String, Map<String, ProductOptionValue>> savedValues) {
        if (combo.selections().isEmpty()) {
            return OptionCombination.none(productId, productTitle);
        }
        List<Pick> picks = new ArrayList<>();
        for (Axis axis : draft.axes()) {
            String normalized = combo.selections().get(axis.key());
            picks.add(new Pick(savedAxes.get(axis.key()), savedValues.get(axis.key()).get(normalized)));
        }
        return OptionCombination.of(productId, picks);
    }

    /** SKU 중복은 검증기가 요청 안에서 먼저 거른다 — 새 상품이라 DB 에 다른 SKU 가 있을 수 없다. */
    private ProductOption saveOption(Combo combo, OptionCombination combination) {
        return options.saveAndFlush(ProductOption.of(combo.sku(), combo.price(), combo.priceOverridden(), combination));
    }

    /**
     * 등록 기록에 고정한 계획. 비는 경우는 셋이다 — 계획 칸이 생기기 전의 행(JSON null), 칸 이름 · 모양이 바뀌어 엄격하게 못 읽는 저장본,
     * 행의 상품과 다른 상품의 계획. 뒤의 둘은 있어서는 안 되는 일이라 경고 로그를 남긴다.
     */
    private static Optional<RegistrationPlan> storedPlan(ProductRegistration registration) {
        RegistrationPlan plan;
        try {
            plan = PLAN_JSON.readValue(registration.getPlanPayload(), RegistrationPlan.class);
        } catch (JacksonException e) {
            log.warn("등록 기록의 계획을 읽지 못했다. productId={} cause={}", registration.getProductId(), e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (plan != null && !registration.getProductId().equals(plan.productId())) {
            log.warn("등록 기록의 계획이 다른 상품의 것이다. productId={} planProductId={}", registration.getProductId(), plan.productId());
            return Optional.empty();
        }
        return Optional.ofNullable(plan);
    }

    private static BusinessException blocked(Long productId, String blockedReason) {
        return new BusinessException(CatalogErrorCode.REGISTRATION_BLOCKED, Map.of("productId", productId, "blockedReason", blockedReason));
    }

    private ProductRegistration saveRegistration(Long productId, String idempotencyKey, boolean visible, String planPayload) {
        try {
            return registrations.saveAndFlush(ProductRegistration.start(productId, idempotencyKey, visible, planPayload));
        } catch (DataIntegrityViolationException e) {
            // 같은 새 키가 동시에 들어왔다 — 먼저 커밋한 쪽이 이긴다. 이 트랜잭션(상품 포함)은 통째로 돌아간다
            if (ConstraintViolations.mentions(e, "uq_registration_key")) {
                throw new BusinessException(CatalogErrorCode.REGISTRATION_IN_PROGRESS);
            }
            throw e;
        }
    }

    /**
     * 검증기의 콜레이션 흉내({@code collationKey})가 못 잡는 중복 — 확장 문자(ß=ss · Æ=AE · Œ=OE, MySQL 8.4.11 실측) — 은
     * DB 의 UNIQUE 가 최종 판정한다. 그 1062 를 500 대신 400 으로 돌려준다. id 가 IDENTITY 라 INSERT 는 save() 에서 바로 나가므로
     * (실측: flush 전에 예외) 저장 루프째 감싸고, 축 · 영역 단위로 묶어 어느 칸인지 짚는다.
     */
    private static void saveOrReject(Runnable save, String constraint, String field, String message) {
        try {
            save.run();
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.mentions(e, constraint)) {
                throw ValidationFailures.of(field, message);
            }
            throw e;
        }
    }
}
