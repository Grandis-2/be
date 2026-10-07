package com.grandis.nova.catalog.support;

import com.grandis.nova.catalog.option.OptionCombination;
import com.grandis.nova.catalog.option.OptionText;
import com.grandis.nova.catalog.option.ProductOptions;
import com.grandis.nova.catalog.option.ProductOptions.Axis;
import com.grandis.nova.catalog.option.ProductOptions.Image;
import com.grandis.nova.catalog.option.ProductOptions.Section;
import com.grandis.nova.catalog.option.ProductOptions.Value;
import com.grandis.nova.common.UuidBinary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 테스트 데이터. 마이그레이션 이전 칼럼만으로 INSERT 한다 — 다른 모듈(preorder · order)의 픽스처가 그렇게 넣으므로
 * 새 칼럼의 DEFAULT 가 그 행들을 유효하게 지키는지도 이 픽스처가 같이 검증한다.
 *
 * 매번 새 행을 만들고 지우지 않는다. id 와 유일 칸은 UUID 로 채워 테스트끼리 겹치지 않으므로
 * 커밋하는 테스트와 롤백하는 테스트가 같은 컨테이너를 순서 상관없이 쓸 수 있다. id 는 여기서 만들어 INSERT 하고 돌려준다.
 */
public class ShopFixtures {

    public static final BigDecimal OPTION_PRICE = new BigDecimal("1250000");

    private final JdbcTemplate jdbcTemplate;

    public ShopFixtures(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 새 상위 카테고리. 다른 모듈 픽스처와 같은 모양(parent 없이). */
    public UUID category() {
        return insert("""
                INSERT INTO categories (id, name, created_at, updated_at)
                VALUES (?, '스마트폰', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
    }

    /** 표시 순서를 정한 상위 카테고리. */
    public UUID category(String name, int sortOrder) {
        return insert("""
                INSERT INTO categories (id, name, sort_order, created_at, updated_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, name, sortOrder);
    }

    /** 상위 아래 하위 카테고리. */
    public UUID childCategory(UUID parentId, String name) {
        return childCategory(parentId, name, 0);
    }

    public UUID childCategory(UUID parentId, String name, int sortOrder) {
        return insert("""
                INSERT INTO categories (id, name, sort_order, parent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, name, sortOrder, parentId);
    }

    public UUID product(String saleMode, String status) {
        return product(category(), saleMode, status);
    }

    public UUID product(UUID categoryId, String saleMode, String status) {
        return insert("""
                INSERT INTO products (id, category_id, sale_mode, title, status, created_at, updated_at)
                VALUES (?, ?, ?, 'Nova 1', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, categoryId, saleMode, status);
    }

    /** 제목 · 검색 키워드까지 지정한 상품. 목록 시험이 자기 상품만 골라내는 데 쓴다. */
    public UUID product(UUID categoryId, String saleMode, String status, String title, String tags) {
        return insert("""
                INSERT INTO products (id, category_id, sale_mode, title, tags, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, categoryId, saleMode, title, tags, status);
    }

    /** 등록 API 로 들어온 상품의 멱등 키. 노출은 이것이 아니라 판매 방식별 준비(회차 · 재고 행)가 정한다 — {@link #campaign} · {@link #inventory}. */
    public void registration(UUID productId) {
        registration(productId, unique());
    }

    /** preorder 소유 표. catalog 코드는 쓰지 않고 시험 데이터로만 넣는다. */
    public void campaign(UUID productId, Instant opensAt, Instant closesAt) {
        update("""
                INSERT INTO preorder_campaigns (product_id, opens_at, closes_at, created_at, updated_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, utc(opensAt), utc(closesAt));
    }

    /** preorder 소유 표. preorder 가 회차 오픈 시각을 옮기는 것을 흉내 낸다(시험 데이터). */
    public void moveCampaignOpensAt(UUID productId, Instant opensAt) {
        update("UPDATE preorder_campaigns SET opens_at = ? WHERE product_id = ?", utc(opensAt), productId);
    }

    /** order 소유 표. 가용 = total - reserved - sold. */
    public void inventory(UUID optionId, int total, int reserved, int sold) {
        update("""
                INSERT INTO option_inventories (option_id, stock_total, stock_reserved, stock_sold, updated_at)
                VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, optionId, total, reserved, sold);
    }

    /** 필터 JSON 까지 넣은 옵션. */
    public UUID optionWithAttributes(UUID productId, String status, BigDecimal price, String title, String filterJson) {
        return insert("""
                INSERT INTO product_options (id, product_id, sku, title, price, filter_attributes, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, unique(), title, price, filterJson, status);
    }

    public UUID option(UUID productId, String status, BigDecimal price) {
        return insert("""
                INSERT INTO product_options (id, product_id, sku, title, price, status, created_at, updated_at)
                VALUES (?, ?, ?, '옵션', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, unique(), price, status);
    }

    /** DB 는 UTC 벽시계 시각을 담는다. Timestamp 로 넘기면 JVM 시간대로 바뀌어 들어간다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * 일반 상품의 판매 방식별 준비 — order 가 등록 이벤트로 만드는 초기 재고 행을 옵션마다 재고 0 으로 넣는다.
     * 이미 재고 행이 하나라도 있으면(시험이 재고를 직접 넣었으면) 이미 준비된 것이라 아무것도 하지 않는다 — 시험이 일부러 비워 둔 옵션의
     * "재고 행 없음" 을 지키기 위해서다. 재고 0 행은 재고 행이 없을 때와 품절 판정이 같다(가용 0).
     * 옵션이 없는 상품은 준비될 수 없다 — 실제 등록은 조합이 하나도 없으면 400 이라 그런 상품이 생기지 않는다. 그래서 오류로 알린다.
     */
    public void stockReady(UUID productId) {
        Integer inventoryRows = queryForObject("""
                SELECT COUNT(*) FROM option_inventories i JOIN product_options o ON o.id = i.option_id WHERE o.product_id = ?
                """, Integer.class, productId);
        if (inventoryRows != null && inventoryRows > 0) {
            return;
        }
        int inserted = update("""
                INSERT INTO option_inventories (option_id, stock_total, stock_reserved, stock_sold, updated_at)
                SELECT o.id, 0, 0, 0, UTC_TIMESTAMP(6) FROM product_options o WHERE o.product_id = ?
                """, productId);
        if (inserted == 0) {
            throw new IllegalStateException("옵션 없는 일반 상품 " + productId + " 은 준비될 수 없다 — 시험이 옵션을 먼저 넣는다");
        }
    }

    public UUID option(UUID productId, String status) {
        return insert("""
                INSERT INTO product_options (id, product_id, sku, title, price, status, created_at, updated_at)
                VALUES (?, ?, ?, '블랙 / 256GB', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, unique(), OPTION_PRICE, status);
    }

    public UUID optionWithCombination(UUID productId, String combinationKey) {
        return insert("""
                INSERT INTO product_options (id, product_id, sku, title, price, combination_key, status, created_at, updated_at)
                VALUES (?, ?, ?, '블랙 / 256GB', ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, unique(), OPTION_PRICE, combinationKey);
    }

    /** 상품 옵션 문서의 축 하나를 가리킨다 — 축은 문서 안에 있어 행 id 가 없다. */
    public record AxisRef(UUID productId, String key) {
    }

    /** 축을 position 자리에 끼운다(문서 배열 순서가 곧 표시 순서). 라벨은 키와 같게. */
    public AxisRef axis(UUID productId, String axisKey, int position) {
        return axis(productId, axisKey, axisKey, position);
    }

    public AxisRef axis(UUID productId, String axisKey, String label, int position) {
        ProductOptions document = options(productId);
        List<Axis> axes = new ArrayList<>(document.axes());
        axes.add(Math.min(position, axes.size()), new Axis(axisKey, label, List.of()));
        write(productId, new ProductOptions(axes, document.defaultImages(), document.detailImages()));
        return new AxisRef(productId, axisKey);
    }

    public String value(AxisRef axis, String normalizedValue, int position) {
        return value(axis, normalizedValue, normalizedValue, position);
    }

    /** 표시값과 정규화값을 갈라 넣는다 — 둘을 같게 넣으면 어느 칸으로 비교하는지 시험이 못 가른다. */
    public String value(AxisRef axis, String displayValue, String normalizedValue, int position) {
        return value(axis, displayValue, normalizedValue, BigDecimal.ZERO, position);
    }

    /** 값을 position 자리에 끼우고 새 값 id 를 돌려준다. */
    public String value(AxisRef axis, String displayValue, String normalizedValue, BigDecimal surcharge, int position) {
        String id = ProductOptions.newValueId();
        ProductOptions document = options(axis.productId());
        Axis target = document.axis(axis.key()).orElseThrow();
        List<Value> values = new ArrayList<>(target.values());
        values.add(Math.min(position, values.size()), new Value(id, displayValue, normalizedValue, null, surcharge, List.of()));
        write(axis.productId(), document.withAxis(new Axis(target.key(), target.label(), values)));
        return id;
    }

    /**
     * 옵션이 그 값을 골랐다 — 조합 키에 값 id 를 더하고, 필터 축(color · storage)이면 필터 JSON 에 정규화값을 넣는다. 앱이 같은 조합에서 둘을
     * 같이 쓰는 것과 같은 모양이다(목록 필터는 필터 JSON, 상세는 조합 키를 읽는다).
     */
    public void selection(UUID productId, UUID optionId, AxisRef axis, String valueId) {
        ProductOptions document = options(productId);
        Value value = document.axis(axis.key()).flatMap(a -> a.valueById(valueId)).orElseThrow();
        String key = queryForObject("SELECT combination_key FROM product_options WHERE id = ?", String.class, optionId);
        List<String> ids = new ArrayList<>(OptionCombination.valueIdsOf(key));
        ids.add(valueId);
        // 키 규칙은 앱의 것 그대로 — 문서에서 고른 값으로 조합을 만들어 키를 받는다
        update("UPDATE product_options SET combination_key = ? WHERE id = ?",
                OptionCombination.of(productId, document.picksOf(ids)).combinationKey(), optionId);
        if (OptionText.isFilterAxis(axis.key())) {
            update("""
                    UPDATE product_options SET filter_attributes = JSON_SET(COALESCE(filter_attributes, JSON_OBJECT()), CONCAT('$.', ?), ?)
                     WHERE id = ?""", axis.key(), value.normalized(), optionId);
        }
    }

    public void image(UUID productId, String kind, String bundleKey, int position, boolean primary) {
        image(productId, kind, bundleKey, position, primary, "https://img.example/x.jpg");
    }

    /**
     * 사진 한 장을 position 자리에. GALLERY 의 묶음 키가 ''이면 기본 묶음, 아니면 그 정규화값의 색상 값 아래, DETAIL 은 그 영역 아래.
     * 사진은 색상 값 아래에 살므로 color 축 · 그 값이 없으면 끝에 만든다(넣은 순서가 곧 색상 순서다). 썸네일 칸도 앱과 같은 규칙으로 다시 쓴다.
     */
    public void image(UUID productId, String kind, String bundleKey, int position, boolean primary, String url) {
        ProductOptions document = options(productId);
        Image image = new Image(url, primary);
        if ("DETAIL".equals(kind)) {
            List<Section> sections = new ArrayList<>(document.detailImages());
            int at = indexOfSection(sections, bundleKey);
            List<Image> images = new ArrayList<>(at < 0 ? List.of() : sections.get(at).images());
            images.add(Math.min(position, images.size()), image);
            if (at < 0) {
                sections.add(new Section(bundleKey, images));
            } else {
                sections.set(at, new Section(bundleKey, images));
            }
            write(productId, new ProductOptions(document.axes(), document.defaultImages(), sections));
        } else if (bundleKey.isEmpty()) {
            List<Image> images = new ArrayList<>(document.defaultImages());
            images.add(Math.min(position, images.size()), image);
            write(productId, new ProductOptions(document.axes(), images, document.detailImages()));
        } else {
            if (document.axis(OptionText.COLOR).isEmpty()) {
                axis(productId, OptionText.COLOR, Integer.MAX_VALUE);
                document = options(productId);
            }
            if (document.axis(OptionText.COLOR).flatMap(a -> a.valueByNormalized(bundleKey)).isEmpty()) {
                value(new AxisRef(productId, OptionText.COLOR), bundleKey, Integer.MAX_VALUE);
                document = options(productId);
            }
            Axis color = document.axis(OptionText.COLOR).orElseThrow();
            List<Value> values = color.values().stream().map(v -> {
                if (!v.normalized().equals(bundleKey)) {
                    return v;
                }
                List<Image> images = new ArrayList<>(v.images());
                images.add(Math.min(position, images.size()), image);
                return new Value(v.id(), v.value(), v.normalized(), v.hex(), v.surcharge(), images);
            }).toList();
            write(productId, document.withAxis(new Axis(color.key(), color.label(), values)));
        }
    }

    /** 등록 API 로 들어온 상품의 멱등 키. */
    public void registration(UUID productId, String idempotencyKey) {
        update("UPDATE products SET idempotency_key = ? WHERE id = ?", idempotencyKey, productId);
    }

    /** 그 축에서 정규화값으로 값 id 를 찾는다. */
    public String valueId(UUID productId, String axisKey, String normalized) {
        return options(productId).axis(axisKey).flatMap(axis -> axis.valueByNormalized(normalized)).orElseThrow().id();
    }

    public ProductOptions options(UUID productId) {
        return ProductOptions.parse(queryForObject("SELECT options FROM products WHERE id = ?", String.class, productId));
    }

    private void write(UUID productId, ProductOptions document) {
        update("UPDATE products SET options = ?, thumbnail_url = ? WHERE id = ?",
                document.toJson(), document.thumbnailUrl(), productId);
    }

    private static int indexOfSection(List<Section> sections, String section) {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).section().equals(section)) {
                return i;
            }
        }
        return -1;
    }

    public static String unique() {
        return UUID.randomUUID().toString();
    }

    /** id 를 만들어 첫 자리표에 넣는다. */
    private UUID insert(String sql, Object... args) {
        UUID id = UUID.randomUUID();
        Object[] withId = new Object[args.length + 1];
        withId[0] = id;
        System.arraycopy(args, 0, withId, 1, args.length);
        update(sql, withId);
        return id;
    }

    private int update(String sql, Object... args) {
        return jdbcTemplate.update(sql, bound(args));
    }

    private <T> T queryForObject(String sql, Class<T> type, Object... args) {
        return jdbcTemplate.queryForObject(sql, type, bound(args));
    }

    /** UUID 인자는 BINARY(16) 칸에 맞춰 바이트로 바꾼다. */
    private static Object[] bound(Object... args) {
        Object[] bound = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            bound[i] = args[i] instanceof UUID uuid ? UuidBinary.toBytes(uuid) : args[i];
        }
        return bound;
    }
}
