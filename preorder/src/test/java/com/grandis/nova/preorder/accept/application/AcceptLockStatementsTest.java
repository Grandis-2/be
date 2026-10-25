package com.grandis.nova.preorder.accept.application;

import com.grandis.nova.preorder.admission.AdmissionTicket;
import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.integration.catalog.ProductCatalog;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.support.CampaignRowLock;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 접수 트랜잭션은 다른 쓰기를 마친 뒤 회차 행을 잠그므로, 잠금 읽기부터 커밋 전까지의 문장 수가 잠금 구간의 왕복 수다.
 * 세션의 Questions 로 센다(재는 SHOW 자신 포함). 커밋 때 나갈 쓰기는 flush 로 먼저 내보내 잠금 구간에 센다.
 */
@PreorderIntegrationTest
class AcceptLockStatementsTest {

    /** 회차 행 읽기(판정) · 같은 접수 키와 마지막 접수 · 예약 · 이력 · 작업 · 아웃박스. */
    static final long BEFORE_LOCK = 6;

    /** 회차 잠금 읽기 · 순번 카운터 · 예약에 순번 채우기. 차수는 원천 캐시에서 고른다. */
    static final long LOCKED = 3;

    @Autowired
    PreorderAcceptTransaction acceptTransaction;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EntityManager entityManager;

    @Autowired
    DataSource dataSource;

    @MockitoSpyBean
    Campaigns campaigns;

    @MockitoBean
    CatalogClient catalogClient;

    @Test
    void 접수_한_건은_다른_쓰기를_마친_뒤_잠그고_잠금_구간은_문장_셋이다() {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        PreorderProduct product = fixtures.openPreorderProduct();
        Optional<ProductCatalog> catalog = Optional.of(CatalogStubs.preorderProduct(product.productId(),
                CatalogStubs.activeOption(product.optionId())).data());
        accept(product, catalog, fixtures.customer());

        UUID customerId = fixtures.customer();
        // 순번의 차수는 오픈 뒤에 읽어 둔 원천 캐시에서 고른다 — 캐시를 채운 뒤의 접수를 잰다
        campaigns.findCached(product.productId());
        AtomicLong locking = new AtomicLong();
        // 스파이를 감싼 트랜잭션 프록시가 MANDATORY 를 검사하므로 스텁도 트랜잭션 안에서 건다
        transactionTemplate.executeWithoutResult(status -> doAnswer(invocation -> {
            locking.set(questions());
            return invocation.callRealMethod();
        }).when(campaigns).lockForAccept(any()));

        long[] statements = transactionTemplate.execute(status -> {
            long before = questions();
            accept(product, catalog, customerId);
            entityManager.flush();
            long after = questions();
            return new long[]{locking.get() - before - 1, after - locking.get() - 1};
        });

        assertThat(statements[0]).as("잠그기 전").isEqualTo(BEFORE_LOCK);
        assertThat(statements[1]).as("잠금 구간").isEqualTo(LOCKED);
    }

    /** 잠금 대기 시한은 Spring 잠금 예외로 올라와야 접수가 500 이 아니라 503 + Retry-After 로 돌려보낸다. */
    @Test
    void 회차_잠금_대기_시한은_스프링_잠금_예외로_올라온다() throws SQLException {
        PreorderProduct product = new ShopFixtures(jdbcTemplate).openPreorderProduct();
        try (CampaignRowLock ignored = CampaignRowLock.hold(dataSource, product.productId())) {
            transactionTemplate.executeWithoutResult(status -> {
                // 트랜잭션의 커넥션에만 짧은 시한을 건다 — 풀로 돌아가기 전에 되돌린다
                jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = 1");
                try {
                    campaigns.findForAccept(product.productId());
                    assertThatThrownBy(() -> campaigns.lockForAccept(product.productId()))
                            .isInstanceOf(PessimisticLockingFailureException.class);
                } finally {
                    jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = DEFAULT");
                    status.setRollbackOnly();
                }
            });
        }
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
