package com.grandis.nova.preorder.accept;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.ErrorCode;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.support.AdmissionTickets;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.Concurrently;
import com.grandis.nova.preorder.support.PreorderCancels;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.IntFunction;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 접수 동시성(계획서 P2-5). 실제 접수 서비스를 여러 스레드로 동시에 부른다.
 *
 * 공통 불변식: 커밋된 예약의 순번은 1부터 빈틈 · 중복이 없고, 회차 카운터는 커밋된 건수 + 1 이다
 * (실패한 접수가 올린 카운터는 롤백된다).
 */
@PreorderIntegrationTest
class PreorderAcceptConcurrencyTest {

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    PreorderLedger ledger;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    PreorderProduct product;
    Long otherOptionId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        product = fixtures.openPreorderProduct();
        otherOptionId = fixtures.option(product.productId(), "ACTIVE");
        given(catalogClient.getProduct(product.productId())).willReturn(CatalogStubs.preorderProduct(
                product.productId(), CatalogStubs.activeOption(product.optionId()), CatalogStubs.activeOption(otherOptionId)));
    }

    @RepeatedTest(3)
    void 같은_키를_동시에_여러_번_보내도_예약_하나_순번_하나() throws Exception {
        Long customerId = fixtures.customer();
        String ticket = AdmissionTickets.issue(product.productId(), customerId, Instant.now());
        int requests = 10;

        List<Outcome> outcomes = concurrently(requests, i -> () -> acceptService.acceptByCustomer(customerId,
                product.productId(), product.productId(), product.optionId(), "same-key-0001", ticket));

        assertThat(outcomes).allMatch(Outcome::accepted);
        assertThat(outcomes.stream().map(o -> o.result().preorder().getPreorderToken()).distinct()).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.result().replayed())).hasSize(1);
        assertThat(committedPositions()).containsExactly(1L);
        assertThat(nextQueuePosition()).isEqualTo(2);
    }

    @RepeatedTest(3)
    void 같은_모델을_옵션을_바꿔_동시에_접수해도_한_건만_남는다() throws Exception {
        Long customerId = fixtures.customer();
        // 입장권은 30초 창마다 달라진다. 서로 다른 입장권 4장으로 입장권 UNIQUE 가 아니라 활성 예약 UNIQUE 를 겨룬다.
        List<String> tickets = LongStream.range(0, 4)
                .mapToObj(i -> AdmissionTickets.issue(product.productId(), customerId,
                        Instant.now().minusSeconds(i * AdmissionTickets.WINDOW_SECONDS)))
                .toList();

        List<Outcome> outcomes = concurrently(tickets.size(), i -> () -> acceptService.acceptByCustomer(customerId,
                product.productId(), product.productId(), i % 2 == 0 ? product.optionId() : otherOptionId,
                "option-key-" + i + "000", tickets.get(i)));

        assertThat(outcomes.stream().filter(Outcome::accepted)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.accepted()))
                .allMatch(o -> o.error() == PreorderErrorCode.ACTIVE_PREORDER_EXISTS);
        assertThat(committedPositions()).containsExactly(1L);
        assertThat(nextQueuePosition()).as("실패한 접수의 순번은 롤백된다").isEqualTo(2);
    }

    @RepeatedTest(3)
    void 같은_키로_다른_모델을_동시에_보내면_하나만_받고_나머지는_422() throws Exception {
        // 모델이 다르면 서로 다른 회차를 잠가 재전송 확인을 동시에 지나칠 수 있다. 그때는 접수 키 UNIQUE 가 막고
        // 롤백 뒤 다시 확인해 422 가 된다. 차례로 들어오면 재전송 확인이 먼저 422 로 막는다 — 어느 쪽이든 결과가 같아야 한다.
        // 충돌 분기 자체는 PreorderAcceptServiceTest 가 충돌을 직접 만들어 확인한다.
        PreorderProduct other = fixtures.openPreorderProduct();
        given(catalogClient.getProduct(other.productId())).willReturn(
                CatalogStubs.preorderProduct(other.productId(), CatalogStubs.activeOption(other.optionId())));
        Long customerId = fixtures.customer();
        List<PreorderProduct> targets = List.of(product, other);

        List<Outcome> outcomes = concurrently(targets.size(), i -> {
            PreorderProduct target = targets.get(i);
            String ticket = AdmissionTickets.issue(target.productId(), customerId, Instant.now());
            return () -> acceptService.acceptByCustomer(customerId, target.productId(), target.productId(),
                    target.optionId(), "shared-key-01", ticket);
        });

        assertThat(outcomes.stream().filter(Outcome::accepted)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.accepted()))
                .singleElement()
                .extracting(Outcome::error).isEqualTo(PreorderErrorCode.KEY_PAYLOAD_MISMATCH);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM preorders WHERE customer_id = ? AND idempotency_key = 'shared-key-01'
                """, Integer.class, customerId)).isEqualTo(1);
    }

    @RepeatedTest(3)
    void 다른_회원이_동시에_접수하면_순번이_빈틈_중복_없이_1부터_이어진다() throws Exception {
        int requests = 20;
        List<Long> customers = LongStream.range(0, requests).mapToObj(i -> fixtures.customer()).toList();

        List<Outcome> outcomes = concurrently(requests, i -> () -> acceptByCustomer(customers.get(i), "member-key-01"));

        assertThat(outcomes).allMatch(Outcome::accepted);
        assertThat(committedPositions())
                .containsExactlyElementsOf(LongStream.rangeClosed(1, requests).boxed().toList());
        assertThat(nextQueuePosition()).isEqualTo(requests + 1);
    }

    @Test
    void 성공과_실패가_섞여도_커밋된_순번에는_빈틈이_없다() throws Exception {
        int members = 10;
        List<Long> customers = LongStream.range(0, members).mapToObj(i -> fixtures.customer()).toList();
        // 짝수 회원은 같은 모델을 두 번(다른 키 · 다른 창의 입장권) 보낸다 — 한 번은 활성 예약 UNIQUE 로 실패한다.
        List<Callable<AcceptResult>> calls = new ArrayList<>();
        for (int i = 0; i < members; i++) {
            Long customer = customers.get(i);
            calls.add(() -> acceptByCustomer(customer, "mixed-key-a"));
            if (i % 2 == 0) {
                String olderTicket = AdmissionTickets.issue(product.productId(), customer,
                        Instant.now().minusSeconds(AdmissionTickets.WINDOW_SECONDS));
                calls.add(() -> acceptService.acceptByCustomer(customer, product.productId(), product.productId(),
                        product.optionId(), "mixed-key-b", olderTicket));
            }
        }

        List<Outcome> outcomes = concurrently(calls.size(), calls::get);

        assertThat(outcomes.stream().filter(Outcome::accepted)).hasSize(members);
        assertThat(outcomes.stream().filter(o -> !o.accepted()))
                .hasSize(members / 2)
                .allMatch(o -> o.error() == PreorderErrorCode.ACTIVE_PREORDER_EXISTS);
        assertThat(committedPositions())
                .containsExactlyElementsOf(LongStream.rangeClosed(1, members).boxed().toList());
        assertThat(nextQueuePosition()).isEqualTo(members + 1);
    }

    @Test
    void 차수_경계를_넘는_순번은_동시에_들어와도_구간대로_배정된다() throws Exception {
        long start = ShopFixtures.FIRST_BATCH_LAST_POSITION - 1;
        jdbcTemplate.update("UPDATE preorder_campaigns SET next_queue_position = ? WHERE product_id = ?",
                start, product.productId());
        int requests = 4;
        List<Long> customers = LongStream.range(0, requests).mapToObj(i -> fixtures.customer()).toList();

        List<Outcome> outcomes = concurrently(requests, i -> () -> acceptByCustomer(customers.get(i), "boundary-key"));

        assertThat(outcomes).allMatch(Outcome::accepted);
        assertThat(jdbcTemplate.queryForList("""
                SELECT CONCAT(p.queue_position, ':', b.batch_number)
                  FROM preorders p JOIN shipment_batches b ON b.id = p.shipment_batch_id
                 WHERE p.product_id = ? ORDER BY p.queue_position
                """, String.class, product.productId()))
                .containsExactly(start + ":1", (start + 1) + ":1", (start + 2) + ":2", (start + 3) + ":2");
    }

    @Test
    void 취소가_끝난_뒤_다시_신청하면_새_순번을_받는다() throws Exception {
        Long customerId = fixtures.customer();
        AcceptResult first = acceptByCustomer(customerId, "reapply-key-1");
        new PreorderCancels(ledger, transactionTemplate).complete(first.preorder().getId());
        String laterTicket = AdmissionTickets.issue(product.productId(), customerId,
                Instant.now().minusSeconds(AdmissionTickets.WINDOW_SECONDS));

        AcceptResult again = acceptService.acceptByCustomer(customerId, product.productId(), product.productId(),
                product.optionId(), "reapply-key-2", laterTicket);

        assertThat(again.preorder().getQueuePosition()).isEqualTo(first.preorder().getQueuePosition() + 1);
        assertThat(again.replayed()).isFalse();
    }

    private AcceptResult acceptByCustomer(Long customerId, String key) {
        return acceptService.acceptByCustomer(customerId, product.productId(), product.productId(),
                product.optionId(), key, AdmissionTickets.issue(product.productId(), customerId, Instant.now()));
    }

    /** 요청을 한꺼번에 출발시키고 결과를 모은다. 업무 오류(BusinessException) 외의 예외는 테스트 실패다. */
    private static List<Outcome> concurrently(int requests, IntFunction<Callable<AcceptResult>> tasks)
            throws InterruptedException {
        return Concurrently.run(requests, tasks).stream().map(Outcome::of).toList();
    }

    private List<Long> committedPositions() {
        return jdbcTemplate.queryForList(
                "SELECT queue_position FROM preorders WHERE product_id = ? ORDER BY queue_position",
                Long.class, product.productId());
    }

    private long nextQueuePosition() {
        return fixtures.nextQueuePosition(product.productId());
    }

    record Outcome(AcceptResult result, ErrorCode error) {

        static Outcome of(Concurrently.Outcome<AcceptResult> outcome) {
            if (outcome.succeeded()) {
                return new Outcome(outcome.value(), null);
            }
            if (outcome.error() instanceof BusinessException business) {
                return new Outcome(null, business.errorCode());
            }
            throw new AssertionError("업무 오류가 아닌 예외", outcome.error());
        }

        boolean accepted() {
            return result != null;
        }
    }
}
