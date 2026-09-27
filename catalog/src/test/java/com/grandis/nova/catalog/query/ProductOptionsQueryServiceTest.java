package com.grandis.nova.catalog.query;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * (visible, registrationCompleted) 는 한 스냅샷에서 나와야 한다. 완료 트랜잭션(visible=1 + completed_at)이 조회 도중 커밋돼도
 * 응답은 커밋 전 조합이거나 커밋 후 조합이어야지, 한순간도 없던 (true, false) · (false, true) 가 나오면 안 된다.
 *
 * 시험은 둘이다. 모양: 등록 기록을 읽는 SQL 이 정확히 하나이고 그 문장이 products 도 읽는다(= 한 SELECT). 행동: 그 문장 직전에
 * 다른 커넥션으로 완료를 커밋해도 응답이 한 시점의 조합이다({@link CompletionInterleavingInspector}).
 * 상품 먼저 · 등록 따로 읽는 구현은 행동 시험에서 (false, true) 로 떨어지고, 등록 먼저 · 상품 따로 읽는 구현(위험한 방향 —
 * (true, false) 가 나온다)은 훅이 첫 문장 앞에 걸려 행동 시험을 통과하므로 모양 시험이 잡는다. 둘 다 있어야 한다.
 */
@CatalogIntegrationTest
@TestPropertySource(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.grandis.nova.catalog.query.CompletionInterleavingInspector")
class ProductOptionsQueryServiceTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ProductOptionsQueryService service;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        CompletionInterleavingInspector.reset();
    }

    @Test
    @DisplayName("공개 여부와 등록 완료는 한 문장(JOIN)으로 읽는다 — 어느 순서로든 따로 읽으면 스냅샷이 갈린다")
    void readsProductAndRegistrationInOneStatement() {
        Long productId = fixtures.product("PREORDER", "ACTIVE");
        fixtures.registration(productId, ShopFixtures.unique());
        CompletionInterleavingInspector.reset();

        service.findProductOptions(productId);

        List<String> registrationReads = CompletionInterleavingInspector.executed.stream()
                .filter(sql -> sql.contains("product_registrations"))
                .toList();
        assertThat(registrationReads).as("등록 기록을 읽는 문장은 하나").hasSize(1);
        assertThat(registrationReads.getFirst()).as("그 문장이 products 도 읽는다").contains("products");
    }

    @Test
    @DisplayName("조회 도중 완료가 커밋돼도 공개 여부와 완료 여부는 같은 시점의 조합이다")
    void visibilityAndCompletionComeFromOneSnapshot() {
        Long productId = fixtures.product("PREORDER", "ACTIVE");
        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", productId);
        fixtures.registration(productId, ShopFixtures.unique());
        boolean[] hookRan = {false};
        CompletionInterleavingInspector.reset();
        CompletionInterleavingInspector.beforeRegistrationRead = () -> {
            completeOnAnotherConnection(productId);
            hookRan[0] = true;
        };

        ProductOptionsView view = service.findProductOptions(productId);

        assertThat(hookRan[0]).as("훅이 실제로 조회 도중에 돌았다").isTrue();
        assertThat(List.of(view.visible(), view.registrationCompleted()))
                .as("커밋 전 (false,false) 이거나 커밋 후 (true,true)")
                .isIn(List.of(false, false), List.of(true, true));
    }

    /** 이 스레드는 서비스의 읽기 트랜잭션 안이라 같은 커넥션을 쓴다. 다른 스레드 = 다른 커넥션 = 즉시 커밋. */
    private void completeOnAnotherConnection(Long productId) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> updated = executor.submit(() -> jdbcTemplate.update(
                    "UPDATE products p JOIN product_registrations r ON r.product_id = p.id "
                            + "SET p.visible = 1, r.completed_at = UTC_TIMESTAMP(6) WHERE p.id = ?", productId));
            assertThat(updated.get()).isEqualTo(2);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            executor.shutdownNow();
        }
    }
}
