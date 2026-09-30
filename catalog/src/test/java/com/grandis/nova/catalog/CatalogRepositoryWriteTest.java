package com.grandis.nova.catalog;

import com.grandis.nova.catalog.image.ProductImage;
import com.grandis.nova.catalog.image.ProductImageRepository;
import com.grandis.nova.catalog.option.OptionCombination;
import com.grandis.nova.catalog.option.OptionCombination.Pick;
import com.grandis.nova.catalog.option.ProductOptionAxis;
import com.grandis.nova.catalog.option.ProductOptionAxisRepository;
import com.grandis.nova.catalog.option.ProductOptionSelectionRepository;
import com.grandis.nova.catalog.option.ProductOptionValue;
import com.grandis.nova.catalog.option.ProductOptionValueRepository;
import com.grandis.nova.catalog.product.ProductOption;
import com.grandis.nova.catalog.product.ProductOptionRepository;
import com.grandis.nova.catalog.registration.ProductRegistration;
import com.grandis.nova.catalog.registration.ProductRegistrationRepository;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리포지터리(JPA) 경로로 썼을 때도 DB 제약에 닿는지. 스키마 시험은 SQL 로 직접 치므로 이 경로는 따로 잰다 —
 * 직접 할당 키 엔티티는 Spring Data 의 save() 가 merge 를 타서 INSERT 대신 UPDATE 가 나가고, 제약 위반이 오류가 아니라
 * 덮어쓰기나 무시로 끝난다(실측). 그래서 트랜잭션 밖에서 저장소로 두 번 저장해 예외를 단언한다.
 */
@CatalogIntegrationTest
class CatalogRepositoryWriteTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ProductRegistrationRepository registrations;
    @Autowired ProductOptionSelectionRepository selections;
    @Autowired ProductOptionAxisRepository axes;
    @Autowired ProductOptionValueRepository values;
    @Autowired ProductOptionRepository options;
    @Autowired ProductImageRepository images;

    ShopFixtures fixtures;
    TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("완료된 등록 위에 같은 상품으로 다시 시작하면 거절되고 원본이 남는다")
    void secondStartOnSameProductIsRejected() {
        Long productId = fixtures.product("PREORDER", "ACTIVE");
        String firstKey = ShopFixtures.unique();
        registrations.saveAndFlush(ProductRegistration.start(productId, firstKey, true));
        jdbcTemplate.update("UPDATE product_registrations SET completed_at = UTC_TIMESTAMP(6), campaign_set_at = UTC_TIMESTAMP(6) "
                + "WHERE product_id = ?", productId);

        assertThatThrownBy(() -> registrations.saveAndFlush(
                ProductRegistration.start(productId, ShopFixtures.unique(), false)))
                .isInstanceOf(DataIntegrityViolationException.class);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT idempotency_key, completed_at, campaign_set_at, requested_visible "
                        + "FROM product_registrations WHERE product_id = ?", productId);
        assertThat(row.get("idempotency_key")).isEqualTo(firstKey);
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("campaign_set_at")).isNotNull();
        assertThat(row.get("requested_visible")).isEqualTo(true);
    }

    @Test
    @DisplayName("같은 (옵션, 축) 에 다른 값을 저장하면 거절되고 원래 값이 남는다")
    void conflictingSelectionIsRejected() {
        Long productId = fixtures.product("IN_STOCK", "ACTIVE");
        ProductOptionAxis color = axes.findById(fixtures.axis(productId, "color", 0)).orElseThrow();
        ProductOptionValue black = values.findById(fixtures.value(color.getId(), "블랙", 0)).orElseThrow();
        ProductOptionValue white = values.findById(fixtures.value(color.getId(), "화이트", 1)).orElseThrow();
        Long optionId = fixtures.option(productId, "ACTIVE");
        selections.saveAllAndFlush(OptionCombination.of(productId, List.of(new Pick(color, black))).selections(optionId));

        // 같은 옵션 id 에 다른 조합의 선택 행을 걸려는 잘못된 호출
        assertThatThrownBy(() -> selections.saveAllAndFlush(
                OptionCombination.of(productId, List.of(new Pick(color, white))).selections(optionId)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT value_id FROM product_option_selections WHERE option_id = ? AND axis_id = ?", Long.class,
                optionId, color.getId())).isEqualTo(black.getId());
    }

    @Test
    @DisplayName("축이 없는 상품의 옵션은 두 번째 저장을 DB 가 거절한다 — 조회 뒤 INSERT 경합에 기대지 않는다")
    void secondStandaloneOptionIsRejected() {
        Long productId = fixtures.product("IN_STOCK", "ACTIVE");
        options.saveAndFlush(ProductOption.of("ONLY-1", new BigDecimal("1000"), false, OptionCombination.none(productId, "Nova 1")));
        assertThatThrownBy(() -> options.saveAndFlush(
                ProductOption.of("ONLY-2", new BigDecimal("1000"), false, OptionCombination.none(productId, "Nova 1"))))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_option_combination");
        // catalog 밖에서 키 없이 넣은 행은 계속 여럿이어도 된다
        fixtures.option(productId, "ACTIVE");
        fixtures.option(productId, "ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_options WHERE product_id = ?", Long.class, productId))
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("대표 교체는 해제를 먼저 flush 하면 어느 순서로 읽어 들였든 성공한다")
    void primarySwapSucceedsInBothLoadOrders() {
        Long productId = fixtures.product("IN_STOCK", "ACTIVE");
        Long oldId = fixtures.image(productId, "GALLERY", "", 0, true);
        Long newId = fixtures.image(productId, "GALLERY", "", 1, false);

        // 새 대표를 먼저 적재한 순서 — 해제 flush 없이는 여기서 터진다(아래 대조군)
        swapWithFlush(newId, oldId, oldId, newId);
        assertPrimary(newId, oldId);

        // 되돌리기: 이제 newId 가 옛 대표, oldId 가 새 대표다. 적재 순서(newId 먼저)는 같으니 이번엔 "옛 대표를 먼저 적재한" 경우가 된다
        swapWithFlush(newId, oldId, newId, oldId);
        assertPrimary(oldId, newId);
    }

    @Test
    @DisplayName("대조군: 해제와 지정을 한 flush 에 묶으면 새 대표를 먼저 적재한 순서에서 터진다")
    void primarySwapWithoutFlushFailsWhenNewLoadedFirst() {
        Long productId = fixtures.product("IN_STOCK", "ACTIVE");
        Long oldId = fixtures.image(productId, "GALLERY", "", 0, true);
        Long newId = fixtures.image(productId, "GALLERY", "", 1, false);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            ProductImage newer = images.findById(newId).orElseThrow();
            ProductImage older = images.findById(oldId).orElseThrow();
            older.markPrimary(false);
            newer.markPrimary(true);
            entityManager.flush();
        }))
                // EntityManager 를 직접 flush 하면 Spring 번역 없이 Hibernate 예외가 온다
                .isInstanceOf(PersistenceException.class).hasMessageContaining("uq_product_image_primary");
        assertPrimary(oldId, newId);
    }

    /** firstLoaded · secondLoaded 순서로 읽어 들인 뒤, 옛 대표를 해제해 flush 하고 새 대표를 지정한다. */
    private void swapWithFlush(Long firstLoaded, Long secondLoaded, Long from, Long to) {
        transaction.executeWithoutResult(status -> {
            images.findById(firstLoaded).orElseThrow();
            images.findById(secondLoaded).orElseThrow();
            ProductImage older = images.findById(from).orElseThrow();
            ProductImage newer = images.findById(to).orElseThrow();
            older.markPrimary(false);
            images.saveAndFlush(older);
            newer.markPrimary(true);
            images.saveAndFlush(newer);
        });
    }

    private void assertPrimary(Long primaryId, Long otherId) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT primary_marker FROM product_images WHERE id = ?", Integer.class, primaryId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT primary_marker FROM product_images WHERE id = ?", Integer.class, otherId)).isNull();
    }
}
