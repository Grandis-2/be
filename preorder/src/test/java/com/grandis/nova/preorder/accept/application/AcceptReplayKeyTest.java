package com.grandis.nova.preorder.accept.application;

import com.grandis.nova.preorder.admission.AdmissionTicket;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.integration.catalog.ProductCatalog;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 같은 접수 키인지는 DB 가 가른다 — 끝 공백만 다른 키는 칸의 콜레이션에서 같은 키다(재전송, 입장권 확인 전). */
@PreorderIntegrationTest
class AcceptReplayKeyTest {

    @Autowired
    PreorderAcceptTransaction acceptTransaction;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    @Test
    void 끝_공백만_다른_접수_키는_재전송으로_본다() {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        PreorderProduct product = fixtures.openPreorderProduct();
        Optional<ProductCatalog> catalog = Optional.of(CatalogStubs.preorderProduct(product.productId(),
                CatalogStubs.activeOption(product.optionId())).data());
        UUID customerId = fixtures.customer();
        String key = "key-" + ShopFixtures.unique();
        AdmissionTicket ticket = new AdmissionTicket(ShopFixtures.unique(), false, Instant.now().minusSeconds(10));

        AcceptResult first = accept(product, catalog, customerId, key, ticket);
        AcceptResult again = accept(product, catalog, customerId, key + " ", ticket);

        assertThat(again.replayed()).isTrue();
        assertThat(again.preorder().preorderToken()).isEqualTo(first.preorder().preorderToken());
    }

    private AcceptResult accept(PreorderProduct product, Optional<ProductCatalog> catalog, UUID customerId,
                                String key, AdmissionTicket ticket) {
        return transactionTemplate.execute(status -> acceptTransaction.accept(new AcceptCommand(customerId,
                product.productId(), product.optionId(), key, ticket, EventActor.USER, null, null), catalog));
    }
}
