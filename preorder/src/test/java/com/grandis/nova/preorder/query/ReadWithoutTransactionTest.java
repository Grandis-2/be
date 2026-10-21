package com.grandis.nova.preorder.query;

import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderCancels;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import com.grandis.nova.preorder.syncjob.SyncJobs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static com.grandis.nova.preorder.support.AccessTokens.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 자주 부르는 조회는 트랜잭션을 열지 않는다 — 트랜잭션마다 autocommit · 읽기 전용 전환과 COMMIT 문장이 붙는다.
 * 요청을 보낸 스레드가 트랜잭션 관리자에 트랜잭션을 달라고 했는지 센다(다른 스레드의 일은 세지 않는다).
 */
@PreorderIntegrationTest
@AutoConfigureMockMvc
class ReadWithoutTransactionTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    PreorderLedger ledger;

    @Autowired
    TransactionTemplate transactionTemplate;

    @MockitoSpyBean
    PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    SyncJobs syncJobs;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    AcceptFixtures accepts;
    UUID customerId;
    final AtomicInteger opened = new AtomicInteger();
    volatile Thread caller;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        customerId = fixtures.customer();
        willAnswer(invocation -> {
            if (Thread.currentThread() == caller) {
                opened.incrementAndGet();
            }
            return invocation.callRealMethod();
        }).given(transactionManager).getTransaction(any());
    }

    @Test
    void 내_예약_목록_진행_중_상세_이력은_트랜잭션을_열지_않는다() throws Exception {
        String token = AcceptFixtures.tokenOf(accepts.accept(customerId));

        assertNoTransaction(get("/api/v1/preorders").with(customer(customerId)));
        assertNoTransaction(get("/api/v1/preorders/active").with(customer(customerId)));
        assertNoTransaction(get("/api/v1/preorders/{id}", token).with(customer(customerId)));
        assertNoTransaction(get("/api/v1/preorders/{id}/history", token).with(customer(customerId)));
    }

    @Test
    void 결제_가능_여부는_트랜잭션을_열지_않는다() throws Exception {
        AcceptResult accepted = accepts.accept(customerId);

        assertNoTransaction(get("/internal/preorders/{id}/payability", AcceptFixtures.tokenOf(accepted)).with(admin()));
        assertNoTransaction(get("/internal/preorders/ids/{id}/payability", accepted.preorder().id()).with(admin()));
    }

    @Test
    void 오픈_알림_신청_여부_내_신청_목록_신청_끄기는_트랜잭션을_열지_않는다() throws Exception {
        PreorderProduct upcoming = upcoming();

        assertNoTransaction(get("/api/v1/preorders/products/{productId}/open-alert", upcoming.productId())
                .with(customer(customerId)));
        assertNoTransaction(get("/api/v1/preorders/open-alerts").with(customer(customerId)));
        assertNoTransaction(delete("/api/v1/preorders/products/{productId}/open-alert", upcoming.productId())
                .with(customer(customerId)));
    }

    /** 캐시에 없는 회차는 회차 · 차수를 DB 에서 읽는다 — 그 읽기도 트랜잭션 밖이다. */
    @Test
    void 회차_캐시_미스도_트랜잭션을_열지_않는다() throws Exception {
        assertNoTransaction(get("/api/v1/preorders/products/{productId}/shipment-batches", upcoming().productId()));
    }

    /** 화면 단계는 접수 대기(PENDING_SYNC)에서만 등록 작업 상태를 본다 — 그런 행이 없으면 작업을 읽지 않는다. */
    @Test
    void 접수_대기가_아닌_예약만_있으면_등록_작업을_읽지_않는다() throws Exception {
        AcceptResult canceled = accepts.accept(customerId);
        new PreorderCancels(ledger, transactionTemplate).complete(canceled.preorder().id());

        mockMvc.perform(get("/api/v1/preorders").with(customer(customerId))).andExpect(status().isOk());

        verify(syncJobs).registerStatuses(argThat(Collection::isEmpty));
    }

    private PreorderProduct upcoming() {
        Instant opensAt = Instant.now().plus(Duration.ofDays(2));
        return fixtures.preorderProduct(opensAt, opensAt.plus(Duration.ofDays(1)));
    }

    private void assertNoTransaction(RequestBuilder request) throws Exception {
        opened.set(0);
        caller = Thread.currentThread();
        try {
            mockMvc.perform(request).andExpect(status().is2xxSuccessful());
        } finally {
            caller = null;
        }
        assertThat(opened).hasValue(0);
    }
}
