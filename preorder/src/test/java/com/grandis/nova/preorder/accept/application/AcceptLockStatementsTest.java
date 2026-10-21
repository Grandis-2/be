package com.grandis.nova.preorder.accept.application;

import com.grandis.nova.preorder.admission.AdmissionTicket;
import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.integration.catalog.ProductCatalog;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 접수 트랜잭션은 처음부터 회차 행을 잠그므로 커밋 전까지의 문장 수가 곧 잠금 구간의 왕복 수다. 세션의 Questions 로 센다
 * (이 트랜잭션의 커넥션에서 보낸 문장 수, 재는 SHOW 자신 포함). 커밋 때 나갈 INSERT 는 flush 로 먼저 내보낸다.
 */
@PreorderIntegrationTest
class AcceptLockStatementsTest {

    /** 회차 잠금 · 같은 접수 키와 마지막 접수 · 예약 · 순번 카운터 · 아웃박스 · 이력 · 작업. 차수는 원천 캐시에서 고른다. */
    static final long STATEMENTS = 7;

    @Autowired
    PreorderAcceptTransaction acceptTransaction;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EntityManager entityManager;

    @Autowired
    Campaigns campaigns;

    @MockitoBean
    CatalogClient catalogClient;

    @Test
    void 접수_한_건의_잠금_구간은_문장_일곱이다() {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        PreorderProduct product = fixtures.openPreorderProduct();
        Optional<ProductCatalog> catalog = Optional.of(CatalogStubs.preorderProduct(product.productId(),
                CatalogStubs.activeOption(product.optionId())).data());
        accept(product, catalog, fixtures.customer());

        UUID customerId = fixtures.customer();
        // 접수 서비스는 잠그기 전에 원천 캐시를 읽는다(잠금 전 판정) — 그 뒤의 접수를 잰다
        campaigns.findCached(product.productId());

        long statements = transactionTemplate.execute(status -> {
            long before = questions();
            accept(product, catalog, customerId);
            entityManager.flush();
            return questions() - before - 1;
        });

        assertThat(statements).isEqualTo(STATEMENTS);
    }

    private void accept(PreorderProduct product, Optional<ProductCatalog> catalog, UUID customerId) {
        transactionTemplate.executeWithoutResult(status -> acceptTransaction.accept(new AcceptCommand(customerId,
                product.productId(), product.optionId(), "key-" + ShopFixtures.unique(),
                new AdmissionTicket(ShopFixtures.unique(), false, Instant.now().minusSeconds(10)), EventActor.USER,
                null, null), catalog));
    }

    private long questions() {
        return Long.parseLong(jdbcTemplate.queryForMap("SHOW SESSION STATUS LIKE 'Questions'").get("Value").toString());
    }
}
