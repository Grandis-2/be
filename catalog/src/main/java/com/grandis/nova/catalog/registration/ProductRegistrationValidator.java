package com.grandis.nova.catalog.registration;

import com.grandis.nova.catalog.option.OptionCombination;
import com.grandis.nova.catalog.option.ProductOptionAxis;
import com.grandis.nova.catalog.option.ProductOptionValue;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.registration.ProductRegistrationRequest.Combination;
import com.grandis.nova.catalog.registration.ProductRegistrationRequest.GalleryBundle;
import com.grandis.nova.catalog.registration.ProductRegistrationRequest.Image;
import com.grandis.nova.catalog.registration.ProductRegistrationRequest.OptionAxis;
import com.grandis.nova.catalog.registration.ProductRegistrationRequest.OptionValue;
import com.grandis.nova.catalog.web.ValidationFailures;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 등록 요청의 업무 규칙 검사와 정규화. 통과하면 저장에 바로 쓸 {@link Draft} 를 돌려준다. 저장소를 모른다 — 존재 확인(카테고리)은 서비스가 한다.
 *
 * 규칙(설계 §2.1 · §2.1.1 · §2.3 · 제안서 §2·§6):
 * 축 키는 소문자로 접고 유일 · 값은 저장 규칙으로 정규화하고 DB 콜레이션(대소문자 · 악센트 · 전각 무시)과 같은 기준으로 축 안에서 유일 ·
 * 용량은 숫자+단위 · 조합은 축 전부를 덮고 값이 그 축에 있어야 하며 같은 조합 둘 금지 · 조합 상한 · SKU 는 상품 안 유일(비면 값을 '-' 로 잇는다) ·
 * 표시명 120자 · 가격은 정수 원 · 재고는 일반 상품에서 조합마다 필수이고 사전예약은 받지 않는다 · 수동 가격은 보낸 조합만(사용자 결정 2026-09-28) ·
 * 사진 묶음은 color 축이 있으면 색상별만(사용자 결정 2026-09-28), 없으면 기본 묶음 하나 · GALLERY 묶음당 10장 · 대표는 하나(없으면 첫 장) ·
 * DETAIL 은 상한과 대표 자동 지정이 없다 · 사전예약은 회차 + 차수 필수이고 오픈은 지금 + 여유 이후 · 일반은 회차 · 차수를 받지 않는다.
 */
@Component
public class ProductRegistrationValidator {

    static final int MAX_GALLERY_IMAGES_PER_BUNDLE = 10;
    public static final int MAX_SKU_LENGTH = 80;
    public static final int MAX_OPTION_TITLE_LENGTH = 120;
    public static final String STANDALONE_SKU = "STD";
    public static final Pattern STORAGE = Pattern.compile("\\d+(MB|GB|TB)");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    /** 정규화가 끝난 축. values 는 입력 순, key → normalizedValue. */
    public record Axis(String key, String label, List<Value> values) {
        Value value(String normalized) {
            return values.stream().filter(v -> v.normalized().equals(normalized)).findFirst().orElse(null);
        }
    }

    public record Value(String display, String normalized, BigDecimal surcharge) {
    }

    /** 만들 조합 하나. selections 는 축 키 → 정규화값(축 순). stock 은 일반 상품만, 사전예약은 null. */
    public record Combo(Map<String, String> selections, String sku, BigDecimal price, boolean priceOverridden, Integer stock) {
    }

    public record GalleryDraft(String bundleKey, List<Image> items) {
    }

    public record DetailDraft(String section, List<Image> items) {
    }

    public record Draft(List<Axis> axes, List<Combo> combos, List<GalleryDraft> gallery, List<DetailDraft> detail) {
    }

    public Draft validate(ProductRegistrationRequest request, Instant now, Duration minOpenLead) {
        requireSaleModeShape(request, now, minOpenLead);
        List<Axis> axes = normalizeAxes(request.optionAxes());
        List<Combo> combos = buildCombos(request, axes);
        return new Draft(axes, combos, normalizeGallery(request, axes), normalizeDetail(request));
    }

    /**
     * DB 콜레이션(utf8mb4_0900_ai_ci)이 같다고 보는 것 — 대소문자 · 악센트 · 전각 — 을 같게 만드는 비교 키.
     * 값 · 묶음 키 · 영역 이름의 중복 검사에 쓴다.
     * <p><b>근사다.</b> 콜레이션이 분해가 아니라 확장으로 같다고 보는 문자(ß=ss · Æ=AE · Œ=OE, MySQL 8.4.11 실측)는 NFKD 로
     * 안 갈라져 여기서 못 잡는다. 그런 드문 중복은 DB 의 UNIQUE 가 최종 판정하고 서비스가 1062 를 400 으로 돌린다
     * ({@code ProductRegistrationService#saveOrReject}). 이 키는 대부분을 정확한 칸 이름으로 먼저 거르는 1차 그물이다.
     */
    public static String collationKey(String text) {
        String compatible = Normalizer.normalize(text, Normalizer.Form.NFKD);
        return COMBINING_MARKS.matcher(compatible).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static void requireSaleModeShape(ProductRegistrationRequest request, Instant now, Duration minOpenLead) {
        if (request.saleMode() == SaleMode.PREORDER) {
            if (request.campaign() == null) {
                throw ValidationFailures.of("campaign", "사전예약은 회차가 필요합니다.");
            }
            if (request.shipmentBatches().isEmpty()) {
                throw ValidationFailures.of("shipmentBatches", "사전예약은 배송 차수가 필요합니다.");
            }
            Instant opensAt = request.campaign().opensAt();
            if (!request.campaign().closesAt().isAfter(opensAt)) {
                throw ValidationFailures.of("campaign.closesAt", "마감은 오픈 뒤여야 합니다.");
            }
            if (opensAt.isBefore(now.plus(minOpenLead))) {
                throw ValidationFailures.of("campaign.opensAt",
                        "오픈은 지금부터 %d분 뒤여야 합니다.".formatted(minOpenLead.toMinutes()));
            }
            requireBatchShape(request.shipmentBatches());
        } else {
            if (request.campaign() != null) {
                throw ValidationFailures.of("campaign", "일반 상품은 회차를 받지 않습니다.");
            }
            if (!request.shipmentBatches().isEmpty()) {
                throw ValidationFailures.of("shipmentBatches", "일반 상품은 배송 차수를 받지 않습니다.");
            }
        }
        if (!request.warranty().offered() && request.warranty().surcharge().signum() > 0) {
            throw ValidationFailures.of("warranty.surcharge", "보증을 제공하지 않으면 추가금은 0 이어야 합니다.");
        }
        requireWholeWon(request.basePrice(), "basePrice");
        requireWholeWon(request.warranty().surcharge(), "warranty.surcharge");
    }

    /**
     * 배송 차수의 모양 — preorder 가 차수를 받을 때 거는 규칙(api-spec "배송 차수 처리 규칙": 번호 · 시작 순번 양의 정수,
     * 번호 · 시작 유일, 종료 ≥ 시작 또는 마지막만 null, 배송 종료 ≥ 시작)을 ① 전에 같은 기준으로 먼저 거른다.
     * 여기서 안 거르면 ① 은 저장되고 ② 가 거절해 미완료 등록만 남는다. 최종 판정은 preorder 다.
     */
    private static void requireBatchShape(List<ProductRegistrationRequest.ShipmentBatch> batches) {
        Set<Integer> numbers = new HashSet<>();
        Set<Long> starts = new HashSet<>();
        long lastStart = batches.stream().mapToLong(ProductRegistrationRequest.ShipmentBatch::positionFrom).max().orElse(0);
        for (int i = 0; i < batches.size(); i++) {
            var batch = batches.get(i);
            String field = "shipmentBatches[%d]".formatted(i);
            if (batch.batchNumber() < 1) {
                throw ValidationFailures.of(field + ".batchNumber", "차수 번호는 1 이상입니다.");
            }
            if (!numbers.add(batch.batchNumber())) {
                throw ValidationFailures.of(field + ".batchNumber", "같은 차수 번호가 두 번 왔습니다.");
            }
            if (batch.positionFrom() < 1) {
                throw ValidationFailures.of(field + ".positionFrom", "시작 순번은 1 이상입니다.");
            }
            if (!starts.add(batch.positionFrom())) {
                throw ValidationFailures.of(field + ".positionFrom", "같은 시작 순번이 두 번 왔습니다.");
            }
            if (batch.positionTo() == null) {
                if (batch.positionFrom() != lastStart) {
                    throw ValidationFailures.of(field + ".positionTo", "종료 순번은 마지막 차수만 비울 수 있습니다.");
                }
            } else if (batch.positionTo() < batch.positionFrom()) {
                throw ValidationFailures.of(field + ".positionTo", "종료 순번은 시작 순번 이상입니다.");
            }
            if (batch.estimatedShipEnd().isBefore(batch.estimatedShipStart())) {
                throw ValidationFailures.of(field + ".estimatedShipEnd", "배송 종료는 시작 이후여야 합니다.");
            }
        }
    }

    private static List<Axis> normalizeAxes(List<OptionAxis> requested) {
        List<Axis> axes = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < requested.size(); i++) {
            OptionAxis axis = requested.get(i);
            String key = axis.key().strip().toLowerCase(Locale.ROOT);
            if (!keys.add(key)) {
                throw ValidationFailures.of("optionAxes[%d].key".formatted(i), "축 키가 중복입니다: " + key);
            }
            List<Value> values = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int j = 0; j < axis.values().size(); j++) {
                OptionValue value = axis.values().get(j);
                String field = "optionAxes[%d].values[%d]".formatted(i, j);
                String normalized = ProductOptionValue.normalizeFor(key, value.value());
                if (ProductOptionAxis.STORAGE.equals(key) && !STORAGE.matcher(normalized).matches()) {
                    throw ValidationFailures.of(field + ".value", "용량은 숫자와 단위(MB · GB · TB)로 씁니다. 예: 256GB");
                }
                if (!seen.add(collationKey(normalized))) {
                    throw ValidationFailures.of(field + ".value", "같은 축에 같은 값이 있습니다(대소문자 · 악센트 · 전각은 같은 값): " + value.value());
                }
                requireWholeWon(value.surcharge(), field + ".surcharge");
                values.add(new Value(ProductOptionValue.normalize(value.value()), normalized, value.surcharge()));
            }
            axes.add(new Axis(key, axis.label().strip(), values));
        }
        return axes;
    }

    /** 축의 값으로 조합 전부를 만들고 요청의 설정(제외 · SKU · 가격 · 재고)을 입힌다. */
    private static List<Combo> buildCombos(ProductRegistrationRequest request, List<Axis> axes) {
        long total = 1;
        for (Axis axis : axes) {
            total *= axis.values().size();
        }
        if (total > ProductRegistrationRequest.MAX_COMBINATIONS) {
            throw ValidationFailures.of("optionAxes", "조합이 %d개를 넘습니다(%d개).".formatted(ProductRegistrationRequest.MAX_COMBINATIONS, total));
        }
        Map<String, Combination> settings = indexSettings(request, axes);
        List<Combo> combos = new ArrayList<>();
        Set<String> skus = new HashSet<>();
        for (Map<String, String> selections : cartesian(axes)) {
            Combination setting = settings.get(selectionKey(selections));
            if (setting != null && setting.excluded()) {
                continue;
            }
            String field = "combinations[" + selections.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue()).reduce((a, b) -> a + ", " + b).orElse("") + "]";
            String sku = setting != null && setting.sku() != null && !setting.sku().isBlank()
                    ? setting.sku().strip() : defaultSku(selections);
            if (sku.length() > MAX_SKU_LENGTH) {
                throw ValidationFailures.of(field + ".sku", "SKU 는 %d자 이하입니다: %s".formatted(MAX_SKU_LENGTH, sku));
            }
            if (!skus.add(sku)) {
                throw ValidationFailures.of(field + ".sku", "같은 상품 안에 같은 SKU 가 있습니다: " + sku);
            }
            String title = title(axes, selections, request.title());
            if (title.length() > MAX_OPTION_TITLE_LENGTH) {
                throw ValidationFailures.of(field, "옵션 표시명이 %d자를 넘습니다: %s".formatted(MAX_OPTION_TITLE_LENGTH, title));
            }
            BigDecimal computed = request.basePrice();
            for (Axis axis : axes) {
                computed = computed.add(axis.value(selections.get(axis.key())).surcharge());
            }
            boolean overridden = setting != null && setting.price() != null;
            BigDecimal price = overridden ? requireWholeWon(setting.price(), field + ".price") : computed;
            Integer stock = setting == null ? null : setting.stock();
            if (request.saleMode() == SaleMode.IN_STOCK) {
                if (stock == null) {
                    throw ValidationFailures.of(field + ".stock", "일반 상품은 조합마다 초기 재고가 필요합니다(0 허용).");
                }
                if (stock < 0) {
                    throw ValidationFailures.of(field + ".stock", "재고는 0 이상입니다.");
                }
            } else if (stock != null) {
                throw ValidationFailures.of(field + ".stock", "사전예약에는 재고를 넣지 않습니다.");
            }
            combos.add(new Combo(selections, sku, price, overridden, stock));
        }
        if (combos.isEmpty()) {
            throw ValidationFailures.of("combinations", "판매할 조합이 하나도 없습니다.");
        }
        return combos;
    }

    /** 요청의 조합 설정을 정규화한 선택 키로 색인한다. 축을 안 덮거나 없는 값이면 400. 선택의 축 키도 소문자로 접는다. */
    private static Map<String, Combination> indexSettings(ProductRegistrationRequest request, List<Axis> axes) {
        Map<String, Combination> settings = new LinkedHashMap<>();
        for (int i = 0; i < request.combinations().size(); i++) {
            Combination combination = request.combinations().get(i);
            String field = "combinations[%d].selections".formatted(i);
            Map<String, String> given = new LinkedHashMap<>();
            combination.selections().forEach((key, value) -> given.put(key == null ? "" : key.strip().toLowerCase(Locale.ROOT), value));
            if (given.size() != axes.size()) {
                throw ValidationFailures.of(field, "조합은 축 %d개를 모두 골라야 합니다.".formatted(axes.size()));
            }
            Map<String, String> normalized = new TreeMap<>();
            for (Axis axis : axes) {
                String raw = given.get(axis.key());
                if (raw == null || raw.isBlank()) {
                    throw ValidationFailures.of(field, "축 '%s' 의 값이 없습니다.".formatted(axis.key()));
                }
                String value = ProductOptionValue.normalizeFor(axis.key(), raw);
                if (axis.value(value) == null) {
                    throw ValidationFailures.of(field, "축 '%s' 에 없는 값입니다: %s".formatted(axis.key(), raw));
                }
                normalized.put(axis.key(), value);
            }
            if (settings.put(selectionKey(normalized), combination) != null) {
                throw ValidationFailures.of(field, "같은 조합이 두 번 왔습니다.");
            }
        }
        return settings;
    }

    private static List<Map<String, String>> cartesian(List<Axis> axes) {
        List<Map<String, String>> result = new ArrayList<>();
        result.add(new TreeMap<>());
        for (Axis axis : axes) {
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> partial : result) {
                for (Value value : axis.values()) {
                    Map<String, String> extended = new TreeMap<>(partial);
                    extended.put(axis.key(), value.normalized());
                    next.add(extended);
                }
            }
            result = next;
        }
        return result;
    }

    static String selectionKey(Map<String, String> selections) {
        return new TreeMap<>(selections).toString();
    }

    private static String defaultSku(Map<String, String> selections) {
        return selections.isEmpty() ? STANDALONE_SKU : String.join("-", selections.values());
    }

    /** 저장 때 OptionCombination.title() 이 내는 것과 같은 모양(축 순, 표시값을 " / " 로). 길이 검사에 쓴다. */
    /** 표시명 규칙은 {@link OptionCombination#titleOf} 하나다 — 여기서는 저장 전에 길이를 재려고 같은 함수로 미리 만든다. */
    private static String title(List<Axis> axes, Map<String, String> selections, String productTitle) {
        List<String> parts = new ArrayList<>();
        for (Axis axis : axes) {
            parts.add(axis.value(selections.get(axis.key())).display());
        }
        return OptionCombination.titleOf(parts, productTitle);
    }

    private static List<GalleryDraft> normalizeGallery(ProductRegistrationRequest request, List<Axis> axes) {
        Axis color = axes.stream().filter(axis -> ProductOptionAxis.COLOR.equals(axis.key())).findFirst().orElse(null);
        List<GalleryDraft> bundles = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < request.images().gallery().size(); i++) {
            GalleryBundle bundle = request.images().gallery().get(i);
            String field = "images.gallery[%d]".formatted(i);
            boolean hasColor = bundle.color() != null && !bundle.color().isBlank();
            if (color == null && hasColor) {
                throw ValidationFailures.of(field + ".color", "color 축이 없는 상품은 기본 묶음만 받습니다.");
            }
            if (color != null && !hasColor) {
                throw ValidationFailures.of(field + ".color", "color 축이 있는 상품은 색상별 묶음만 받습니다(공통 묶음 없음).");
            }
            String key = "";
            if (hasColor) {
                key = ProductOptionValue.normalizeFor(ProductOptionAxis.COLOR, bundle.color());
                if (color.value(key) == null) {
                    throw ValidationFailures.of(field + ".color", "color 축에 없는 값입니다: " + bundle.color());
                }
            }
            if (!keys.add(collationKey(key))) {
                throw ValidationFailures.of(field + ".color", "같은 묶음이 두 번 왔습니다.");
            }
            if (bundle.items().size() > MAX_GALLERY_IMAGES_PER_BUNDLE) {
                throw ValidationFailures.of(field + ".items", "묶음당 최대 %d장입니다.".formatted(MAX_GALLERY_IMAGES_PER_BUNDLE));
            }
            bundles.add(new GalleryDraft(key, withDefaultPrimary(requireAtMostOnePrimary(bundle.items(), field))));
        }
        return bundles;
    }

    private static List<DetailDraft> normalizeDetail(ProductRegistrationRequest request) {
        List<DetailDraft> sections = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < request.images().detail().size(); i++) {
            var bundle = request.images().detail().get(i);
            String field = "images.detail[%d]".formatted(i);
            String section = ProductOptionValue.normalize(bundle.section());
            if (!names.add(collationKey(section))) {
                throw ValidationFailures.of(field + ".section", "같은 영역이 두 번 왔습니다.");
            }
            // 상세 영역: 상한 · 대표 자동 지정 없음. 대표를 둘 이상 찍는 것만 막는다(DB UNIQUE)
            sections.add(new DetailDraft(section, requireAtMostOnePrimary(bundle.items(), field)));
        }
        return sections;
    }

    private static List<Image> requireAtMostOnePrimary(List<Image> items, String field) {
        if (items.stream().filter(Image::primary).count() > 1) {
            throw ValidationFailures.of(field + ".items", "대표 사진은 하나입니다.");
        }
        return items;
    }

    /** 대표 지정이 없으면 첫 장. 순서는 배열 순. */
    private static List<Image> withDefaultPrimary(List<Image> items) {
        if (items.stream().anyMatch(Image::primary)) {
            return items;
        }
        List<Image> withDefault = new ArrayList<>(items);
        withDefault.set(0, new Image(items.getFirst().url(), true));
        return withDefault;
    }

    /** 0 이상의 정수 원 — 아니면 그 칸의 400. 소수는 decimal(12,0) 칼럼이 조용히 반올림하므로 여기서 거절한다. 수정 API 도 같은 판정을 쓴다. */
    public static BigDecimal requireWholeWon(BigDecimal amount, String field) {
        if (amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > 0) {
            throw ValidationFailures.of(field, "0 이상의 정수 원이어야 합니다.");
        }
        return amount;
    }
}
