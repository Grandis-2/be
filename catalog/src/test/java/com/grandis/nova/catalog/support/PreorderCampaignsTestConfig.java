package com.grandis.nova.catalog.support;

import com.grandis.nova.catalog.integration.PreorderCampaignClient;
import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.web.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * preorder 회차 일정 API(contracts/preorder-internal.md 2절) 자리 — 시험 DB 의 preorder 회차 행(preorder_campaigns)을 preorder 와 같은 규칙으로 돌려준다:
 * 2.1 은 마감 전이고 앱 시계로 10분 안에 열리는 회차를 오픈이 이른 순으로 500건까지, 2.2 는 물은 상품 중 회차가 있는 것. 픽스처가 회차 행을 직접 넣는
 * 기존 시험이 그대로 돈다. 실제 HTTP 계약 · 장애 갈래는 PreorderCampaignsHttpTest 가 본다.
 * 실제 preorder 처럼 부른 쪽의 트랜잭션 밖에서 읽는다(새 트랜잭션) — 부른 쪽의 스냅샷 값이 아니라 그 시점에 커밋된 값이다.
 * DB 트랜잭션 안에서 불리면 {@link #CALLS_IN_TRANSACTION} 에 세고 던진다(재고 대역과 같다 — 연결을 쥔 채 HTTP 를 기다리지 않는다). 예외는 상품 행을 잠근
 * 오픈 판정뿐이다 — 판정이 잠금 아래여야 겹친 수정이 줄 선다(ProductEditService · ProductDeleteService 에서 부른 호출). 같은 스레드의 트랜잭션만 본다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PreorderCampaignsTestConfig {

    public static final AtomicInteger CALLS_IN_TRANSACTION = new AtomicInteger();
    /** 상품 행을 잠근 채 오픈을 판정하는 자리 — 트랜잭션 안에서 불러도 된다. */
    private static final Set<String> LOCKED_JUDGMENTS = Set.of("com.grandis.nova.catalog.edit.ProductEditService",
            "com.grandis.nova.catalog.edit.ProductDeleteService");

    static final Duration LOOKAHEAD = Duration.ofMinutes(10);
    static final int OPEN_LIMIT = 500;

    /** nova.test.fake-preorder-campaigns=false 면 만들지 않는다 — 실제 HTTP 클라이언트를 보는 시험(PreorderCampaignsHttpTest)이 끈다. */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "nova.test.fake-preorder-campaigns", havingValue = "true", matchIfMissing = true)
    PreorderCampaignClient databasePreorderCampaignClient(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager, Clock clock) {
        TransactionTemplate outside = new TransactionTemplate(transactionManager);
        outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new PreorderCampaignClient() {
            @Override
            public ApiResponse<OpenCampaigns> getOpen() {
                requireOutsideTransaction();
                Instant now = clock.instant();
                List<Campaign> items = outside.execute(status -> jdbcTemplate.query("""
                        SELECT product_id, opens_at, closes_at FROM preorder_campaigns
                         WHERE closes_at > ? AND opens_at <= ? ORDER BY opens_at, product_id LIMIT ?
                        """, (rs, rowNum) -> campaign(rs), utc(now), utc(now.plus(LOOKAHEAD)), OPEN_LIMIT));
                return ApiResponse.ok(new OpenCampaigns(items, now));
            }

            @Override
            public ApiResponse<Campaigns> getCampaigns(Collection<UUID> productIds) {
                requireOutsideTransaction();
                List<Campaign> items = new ArrayList<>();
                outside.executeWithoutResult(status -> {
                    for (UUID productId : new LinkedHashSet<>(productIds)) {
                        items.addAll(jdbcTemplate.query("SELECT product_id, opens_at, closes_at FROM preorder_campaigns WHERE product_id = ?",
                                (rs, rowNum) -> campaign(rs), (Object) UuidBinary.toBytes(productId)));
                    }
                });
                return ApiResponse.ok(new Campaigns(items));
            }
        };
    }

    private static void requireOutsideTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        boolean lockedJudgment = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> LOCKED_JUDGMENTS.contains(frame.getClassName())));
        if (!lockedJudgment) {
            CALLS_IN_TRANSACTION.incrementAndGet();
            throw new IllegalStateException("DB 트랜잭션 안에서 preorder 회차를 물었다 — 연결을 쥔 채 HTTP 를 기다린다");
        }
    }

    private static PreorderCampaignClient.Campaign campaign(ResultSet rs) throws SQLException {
        return new PreorderCampaignClient.Campaign(UuidBinary.fromBytes(rs.getBytes("product_id")),
                rs.getObject("opens_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getObject("closes_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
