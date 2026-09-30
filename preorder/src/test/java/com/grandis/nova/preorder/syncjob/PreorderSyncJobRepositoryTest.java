package com.grandis.nova.preorder.syncjob;

import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.NewPreorder;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.syncjob.domain.PreorderSyncJob;
import com.grandis.nova.preorder.syncjob.domain.PreorderSyncJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@PreorderIntegrationTest
@Transactional
class PreorderSyncJobRepositoryTest {

    static final String PAYLOAD = """
            {"preorderToken":"t","customerId":1,"productId":2,"sku":"NOVA-1-BLK-256"}""";

    @Autowired
    PreorderSyncJobRepository jobs;

    @Autowired
    PreorderLedger ledger;

    @Autowired
    JdbcTemplate jdbcTemplate;

    Long preorderId;

    @BeforeEach
    void setUp() {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        PreorderProduct product = fixtures.openPreorderProduct();
        preorderId = ledger.accept(new NewPreorder(ShopFixtures.unique(), fixtures.customer(), product.productId(),
                product.optionId(), product.firstBatchId(), 1, null, ShopFixtures.unique(),
                "Nova 1", "블랙 / 256GB", new BigDecimal("1250000")), EventActor.ADMIN, "테스트").id();
    }

    @Test
    void 등록_작업은_PENDING_으로_만들어지고_전송_내용을_그대로_보관한다() {
        PreorderSyncJob job = jobs.saveAndFlush(PreorderSyncJob.register(preorderId, PAYLOAD));

        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.PENDING);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(request_payload, '$.sku')) FROM preorder_sync_jobs WHERE id = ?",
                String.class, job.getId())).isEqualTo("NOVA-1-BLK-256");
        assertThat(jobs.findByPreorderIdAndJobType(preorderId, SyncJobType.REGISTER)).isPresent();
    }

    @Test
    void 예약당_같은_종류의_작업은_하나뿐이다() {
        jobs.saveAndFlush(PreorderSyncJob.register(preorderId, PAYLOAD));

        assertThatThrownBy(() -> jobs.saveAndFlush(PreorderSyncJob.register(preorderId, PAYLOAD)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_sync_job_type");
    }

    @Test
    void 끝나지_않은_등록_작업은_취소_시작에서_무효화된다() {
        Long jobId = jobs.saveAndFlush(PreorderSyncJob.register(preorderId, PAYLOAD)).getId();

        assertThat(jobs.cancelRegister(preorderId, Instant.now())).isEqualTo(1);
        assertThat(jobs.cancelRegister(preorderId, Instant.now())).isZero();
        assertThat(status(jobId)).isEqualTo("CANCELED");
    }

    @Test
    void DEAD_LETTER_등록_작업도_무효화된다() {
        Long jobId = jobs.saveAndFlush(PreorderSyncJob.register(preorderId, PAYLOAD)).getId();
        workerSets(jobId, "DEAD_LETTER");

        assertThat(jobs.cancelRegister(preorderId, Instant.now())).isEqualTo(1);
        assertThat(status(jobId)).isEqualTo("CANCELED");
    }

    @Test
    void 이미_성공한_등록_작업은_무효화하지_않는다() {
        Long jobId = jobs.saveAndFlush(PreorderSyncJob.register(preorderId, PAYLOAD)).getId();
        workerSets(jobId, "SUCCEEDED");

        assertThat(jobs.cancelRegister(preorderId, Instant.now())).isZero();
        assertThat(status(jobId)).isEqualTo("SUCCEEDED");
    }

    @Test
    void 취소_작업은_등록_무효화의_대상이_아니다() {
        Long cancelJobId = jobs.saveAndFlush(PreorderSyncJob.cancel(preorderId, PAYLOAD)).getId();

        assertThat(jobs.cancelRegister(preorderId, Instant.now())).isZero();
        assertThat(status(cancelJobId)).isEqualTo("PENDING");
    }

    /** worker 가 하는 변경을 흉내 낸다. preorder 코드에는 이 전이가 없다. */
    private void workerSets(Long jobId, String status) {
        jdbcTemplate.update("""
                UPDATE preorder_sync_jobs
                   SET status = ?, dead_lettered_at = IF(? = 'DEAD_LETTER', UTC_TIMESTAMP(6), dead_lettered_at)
                 WHERE id = ?
                """, status, status, jobId);
    }

    private String status(Long jobId) {
        return jdbcTemplate.queryForObject("SELECT status FROM preorder_sync_jobs WHERE id = ?", String.class, jobId);
    }
}
