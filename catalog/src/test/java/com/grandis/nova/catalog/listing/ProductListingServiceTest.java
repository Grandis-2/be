package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.OffsetPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 관리자 목록의 건수와 목록은 두 문장이다. 사이에 다른 커넥션의 INSERT 가 커밋돼도 둘은 같은 스냅샷이어야 한다(REPEATABLE READ).
 * 저장소는 JdbcTemplate 이라 Hibernate 인스펙터로는 틈을 못 만든다 — 저장소 빈을 spy 로 감싸 count 뒤에 끼워 넣는다.
 */
@CatalogIntegrationTest
class ProductListingServiceTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ProductListingService service;
    @MockitoSpyBean ProductListingQueryRepository repository;

    @Test
    @DisplayName("관리자 목록 — 건수와 목록 사이에 커밋된 새 상품은 이번 응답에 섞이지 않는다")
    void adminListCountsAndItemsComeFromOneSnapshot() throws Exception {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        String tag = "t" + ShopFixtures.unique().replace("-", "");
        Long categoryId = fixtures.category();
        Long existing = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "먼저", tag);
        Long[] inserted = {null};
        doAnswer(invocation -> {
            Object total = invocation.callRealMethod();
            inserted[0] = onAnotherConnection(() -> fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "사이에", tag));
            return total;
        }).when(repository).countForAdmin(any());

        OffsetPage<AdminProductListItem> page = service.listForAdmin(new AdminProductListFilter(tag, null, null), 0, 20);

        assertThat(inserted[0]).as("count 뒤에 다른 커넥션이 정말 끼어들었다").isNotNull();
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(AdminProductListItem::productId).containsExactly(existing);
        // 트랜잭션이 끝난 뒤에는 둘 다 보인다 — 대조군
        assertThat(service.listForAdmin(new AdminProductListFilter(tag, null, null), 0, 20).total()).isEqualTo(2);
    }

    private static <T> T onAnotherConnection(java.util.concurrent.Callable<T> work) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(work).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            executor.shutdownNow();
        }
    }
}
