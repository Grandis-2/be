package com.grandis.nova.catalog.migration;

import com.grandis.nova.catalog.option.OptionCombination;
import com.grandis.nova.catalog.option.ProductOptions;
import com.grandis.nova.catalog.option.ProductOptions.Axis;
import com.grandis.nova.catalog.option.ProductOptions.Image;
import com.grandis.nova.catalog.option.ProductOptions.Section;
import com.grandis.nova.catalog.option.ProductOptions.Value;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 옵션 표 → products.options 백필(V202610072048)이 옛 표의 순서 · 대표 · 묶음을 그대로 옮기는지. 앞 버전까지 올린 빈 DB 에 옛 표로 상품을 넣고
 * 그 마이그레이션만 적용한 뒤 문서 · 썸네일 · 멱등 키를 앱의 읽기 규칙({@link ProductOptions})으로 대조한다.
 * 표를 지우는 V202610072049 가 옛 표와 옮긴 칸의 어긋남(동결 창 안의 구버전 쓰기)을 보고 지우기 전에 멈추는지도 본다.
 * 마이그레이션이 shop 스키마를 이름으로 가리켜 다른 시험과 컨테이너를 나눠 쓰지 않는다.
 */
class OptionsBackfillMigrationTest {

    static final String BEFORE = "202610071947";
    static final String BACKFILL = "202610072048";

    static MySQLContainer mysql;
    static SingleConnectionDataSource dataSource;
    static JdbcTemplate jdbc;

    /** 시험마다 빈 DB — 마이그레이션 이력이 시험끼리 섞이지 않게. */
    @BeforeEach
    void start() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("shop");
        mysql.start();
        // 한 커넥션 — LAST_INSERT_ID 는 커넥션마다다
        dataSource = new SingleConnectionDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword(), true);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void stop() {
        if (dataSource != null) {
            dataSource.destroy();
        }
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    @DisplayName("축 · 값은 position 순, 색상 사진은 그 값 아래 position 순, 상세 영역은 이름 순(옛 화면 순서), 썸네일은 앱 규칙(첫 색상의 첫 장)과 같다")
    void backfillKeepsOrderPrimaryAndBundles() {
        migrate(BEFORE);
        long category = insert("INSERT INTO categories (name, created_at, updated_at) VALUES ('폰', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        long phone = product(category, "폰");
        long cable = product(category, "케이블");
        long bare = product(category, "빈 상품");
        long colorNoPhotos = product(category, "색상만");
        long colorFirstImage = product(category, "색상 첫 장");

        // 축은 position 역순으로 넣어 id 순과 갈라 놓는다
        long color = insert(axisSql(phone, "color", "색상", 1));
        long storage = insert(axisSql(phone, "storage", "용량", 0));
        // 옛 값 id 9 · 12 — 문자열 순이면 "12-9", 숫자 순(옛 키)이면 "9-12"
        jdbc.update(valueSql(), 12L, storage, "256 GB", "256GB", new BigDecimal("200000"), 0);
        jdbc.update(valueSql(), 9L, color, "블랙", "블랙", BigDecimal.ZERO, 0);
        jdbc.update(valueSql(), 30L, color, "화이트", "화이트", BigDecimal.ZERO, 1);
        image(phone, "GALLERY", "화이트", 1, true, "https://img/w1.jpg");
        image(phone, "GALLERY", "화이트", 0, false, "https://img/w0.jpg");
        // 색상 축이 있는 상품의 기본 묶음 — 등록 검증이 막기 전의 데이터. 문서에는 옮기되 썸네일은 보지 않는다
        image(phone, "GALLERY", "", 0, true, "https://img/legacy-default.jpg");
        image(phone, "DETAIL", "유의사항", 0, false, "https://img/notice.jpg");
        image(phone, "DETAIL", "디자인", 1, true, "https://img/design1.jpg");
        image(phone, "DETAIL", "디자인", 0, false, "https://img/design0.jpg");
        jdbc.update("INSERT INTO product_registrations (product_id, idempotency_key, created_at, updated_at) VALUES (?, 'key-phone', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                phone);
        jdbc.update("""
                INSERT INTO product_options (product_id, sku, title, price, combination_key, status, created_at, updated_at)
                VALUES (?, 'B-256', '블랙 / 256 GB', 1200000, '9-12', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", phone);
        // 색상 축은 있는데 색상 사진이 없고 기본 묶음만 있는 옛 데이터 — 앱 규칙은 기본 묶음을 보지 않아 썸네일이 없다
        long noPhotoColor = insert(axisSql(colorNoPhotos, "color", "색상", 0));
        jdbc.update(valueSql(), 40L, noPhotoColor, "블랙", "블랙", BigDecimal.ZERO, 0);
        image(colorNoPhotos, "GALLERY", "", 0, true, "https://img/orphan.jpg");
        // 첫 색상(레드, position 0)의 첫 장 — 대표 표시가 둘째 장이어도, 사전순으로 앞서는 그린이 있어도
        long colorAxis = insert(axisSql(colorFirstImage, "color", "색상", 0));
        jdbc.update(valueSql(), 51L, colorAxis, "그린", "그린", BigDecimal.ZERO, 1);
        jdbc.update(valueSql(), 50L, colorAxis, "레드", "레드", BigDecimal.ZERO, 0);
        image(colorFirstImage, "GALLERY", "그린", 0, true, "https://img/g0.jpg");
        image(colorFirstImage, "GALLERY", "레드", 1, true, "https://img/r1.jpg");
        image(colorFirstImage, "GALLERY", "레드", 0, false, "https://img/r0.jpg");
        image(cable, "GALLERY", "", 0, false, "https://img/c0.jpg");
        image(cable, "GALLERY", "", 1, true, "https://img/c1.jpg");

        migrate(BACKFILL);

        ProductOptions phoneDoc = options(phone);
        assertThat(phoneDoc).isEqualTo(new ProductOptions(
                List.of(new Axis("storage", "용량", List.of(new Value("12", "256 GB", "256GB", null, new BigDecimal("200000"), List.of()))),
                        new Axis("color", "색상", List.of(
                                new Value("9", "블랙", "블랙", null, BigDecimal.ZERO, List.of()),
                                new Value("30", "화이트", "화이트", null, BigDecimal.ZERO,
                                        List.of(new Image("https://img/w0.jpg", false), new Image("https://img/w1.jpg", true)))))),
                List.of(new Image("https://img/legacy-default.jpg", true)),
                List.of(new Section("디자인", List.of(new Image("https://img/design0.jpg", false), new Image("https://img/design1.jpg", true))),
                        new Section("유의사항", List.of(new Image("https://img/notice.jpg", false))))));
        assertThat(thumbnail(phone)).as("첫 색상 블랙에 사진이 없다 — 화이트 · 옛 기본 묶음으로 넘어가지 않는다").isNull();
        assertThat(phoneDoc.thumbnailUrl()).isNull();
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM products WHERE id = ?", String.class, phone)).isEqualTo("key-phone");
        // 옛 조합 키는 다시 쓰지 않는다 — 옮긴 값 id 로 앱이 만든 키가 저장된 키와 같아야 같은 조합 UNIQUE 가 계속 맞는다
        String storedKey = jdbc.queryForObject("SELECT combination_key FROM product_options WHERE product_id = ?", String.class, phone);
        assertThat(OptionCombination.of(phone, phoneDoc.picksOf(OptionCombination.valueIdsOf(storedKey))).combinationKey()).isEqualTo(storedKey);

        ProductOptions cableDoc = options(cable);
        assertThat(cableDoc.axes()).isEmpty();
        assertThat(cableDoc.defaultImages()).containsExactly(new Image("https://img/c0.jpg", false), new Image("https://img/c1.jpg", true));
        assertThat(thumbnail(cable)).as("대표(c1)가 아니라 첫 장").isEqualTo("https://img/c0.jpg").isEqualTo(cableDoc.thumbnailUrl());

        assertThat(thumbnail(colorFirstImage)).isEqualTo("https://img/r0.jpg").isEqualTo(options(colorFirstImage).thumbnailUrl());
        assertThat(options(colorNoPhotos).thumbnailUrl()).isNull();
        assertThat(thumbnail(colorNoPhotos)).as("앱이 다음에 문서를 쓸 때와 같은 값 — 기본 묶음으로 넘어가지 않는다").isNull();

        assertThat(options(bare)).isEqualTo(ProductOptions.EMPTY);
        assertThat(thumbnail(bare)).isNull();
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM products WHERE id = ?", String.class, bare)).isNull();

        migrate(null);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = 'shop' AND table_name IN
                       ('product_option_axes', 'product_option_values', 'product_option_selections', 'product_images', 'product_registrations')
                """, String.class)).isEmpty();
    }

    @Test
    @DisplayName("백필 뒤 구버전이 등록 · 값을 더했으면 표 삭제가 지우기 전에 멈추고, 고쳐 repair 하면 지운다 — 새 버전이 쓴 모양은 거짓 경보가 아니다")
    void dropStopsOnDriftBeforeDeletingAnything() {
        migrate(BEFORE);
        long category = insert("INSERT INTO categories (name, created_at, updated_at) VALUES ('폰', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        long phone = product(category, "폰");
        long color = insert(axisSql(phone, "color", "색상", 0));
        jdbc.update(valueSql(), 9L, color, "블랙", "블랙", BigDecimal.ZERO, 0);
        long option = insert("""
                INSERT INTO product_options (product_id, sku, title, price, combination_key, status, created_at, updated_at)
                VALUES (%d, 'B', '블랙', 1000, '9', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""".formatted(phone));
        jdbc.update("INSERT INTO product_option_selections (product_id, option_id, axis_id, value_id) VALUES (?, ?, ?, 9)", phone, option, color);
        migrate(BACKFILL);

        // 1) 구버전이 그 사이에 등록한 상품 — 등록 기록만 있고 상품 칸은 비었다
        long lateProduct = product(category, "늦은 등록");
        jdbc.update("INSERT INTO product_registrations (product_id, idempotency_key, created_at, updated_at) VALUES (?, 'late', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                lateProduct);
        assertDropStopsAndKeepsTables();
        jdbc.update("UPDATE products SET idempotency_key = 'late' WHERE id = ?", lateProduct);
        repairAndCheckStillStops(false);

        // 2) 구버전이 그 사이에 더한 값 — 옛 표에만 있다
        jdbc.update(valueSql(), 10L, color, "화이트", "화이트", BigDecimal.ZERO, 1);
        assertDropStopsAndKeepsTables();
        jdbc.update("DELETE FROM product_option_values WHERE id = 10");
        repairAndCheckStillStops(false);

        // 새 버전이 쓴 모양(등록 기록 없는 키 · 32자 값 id · 선택 없는 조합)은 어긋남이 아니다 — 거짓 경보 없이 지운다
        long newProduct = product(category, "새 버전 등록");
        jdbc.update("UPDATE products SET idempotency_key = 'new', options = JSON_SET(options, '$.axes', JSON_ARRAY()) WHERE id = ?", newProduct);
        jdbc.update("""
                UPDATE products SET options = JSON_ARRAY_APPEND(options, '$.axes[0].values', JSON_OBJECT('id', '0f8c3a1d4b2e4f6a8c9d0e1f2a3b4c5d',
                       'value', '화이트', 'normalized', '화이트', 'hex', NULL, 'surcharge', 0, 'images', JSON_ARRAY())) WHERE id = ?""", phone);
        jdbc.update("""
                INSERT INTO product_options (product_id, sku, title, price, combination_key, status, created_at, updated_at)
                VALUES (?, 'W', '화이트', 1000, '0f8c3a1d4b2e4f6a8c9d0e1f2a3b4c5d', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", phone);
        repairAndCheckStillStops(true);
    }

    private static void assertDropStopsAndKeepsTables() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrate(null))
                .isInstanceOf(FlywayException.class).hasMessageContaining("V202610072049").hasMessageContaining("Error Code : 3141");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'shop' AND table_name IN
                       ('product_option_axes', 'product_option_values', 'product_option_selections', 'product_images', 'product_registrations')
                """, Long.class)).as("아무 표도 지우지 않았다").isEqualTo(5L);
    }

    /** 실패 기록을 지우고 다시 적용한다. last 면 이제 맞으니 표가 지워진다. */
    private static void repairAndCheckStillStops(boolean last) {
        flyway(null).repair();
        if (last) {
            migrate(null);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'shop' AND table_name IN
                           ('product_option_axes', 'product_option_values', 'product_option_selections', 'product_images', 'product_registrations')
                    """, Long.class)).isZero();
        }
    }

    private static void migrate(String target) {
        flyway(target).migrate();
    }

    /**
     * 팀 Flyway CLI(flyway.sh)와 같은 연결 콜레이션(utf8mb4_unicode_ci)으로 마이그레이션을 돌린다. 시험 클래스패스의 드라이버 기본값으로 돌리면
     * 칼럼(utf8mb4_0900_ai_ci)과 식의 콜레이션이 갈리는 문장이 여기서만 통과한다 — CLI 에서는 1267 로 멈춘다(실측).
     */
    private static String cliLikeUrl() {
        return mysql.getJdbcUrl() + (mysql.getJdbcUrl().contains("?") ? "&" : "?") + "connectionCollation=utf8mb4_unicode_ci";
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(cliLikeUrl(), "root", mysql.getPassword())
                .locations("filesystem:" + System.getProperty("nova.migrations-path"))
                .schemas("shop").defaultSchema("shop")
                .createSchemas(false).cleanDisabled(true).validateMigrationNaming(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static long product(long category, String title) {
        return insert("""
                INSERT INTO products (category_id, sale_mode, title, status, created_at, updated_at)
                VALUES (%d, 'IN_STOCK', '%s', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""".formatted(category, title));
    }

    private static String axisSql(long productId, String key, String label, int position) {
        return """
                INSERT INTO product_option_axes (product_id, axis_key, label, position, created_at, updated_at)
                VALUES (%d, '%s', '%s', %d, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""".formatted(productId, key, label, position);
    }

    private static String valueSql() {
        return """
                INSERT INTO product_option_values (id, axis_id, value, normalized_value, surcharge, position, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""";
    }

    private static void image(long productId, String kind, String bundleKey, int position, boolean primary, String url) {
        jdbc.update("""
                INSERT INTO product_images (product_id, kind, bundle_key, position, url, is_primary, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", productId, kind, bundleKey, position, url, primary);
    }

    private static long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static ProductOptions options(long productId) {
        return ProductOptions.parse(jdbc.queryForObject("SELECT options FROM products WHERE id = ?", String.class, productId));
    }

    private static String thumbnail(long productId) {
        return jdbc.queryForObject("SELECT thumbnail_url FROM products WHERE id = ?", String.class, productId);
    }
}
