package com.grandis.nova.order.draw;

import com.grandis.nova.order.draw.domain.model.NewDrawCampaign;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 드로우 회차 공개 조회의 읽기 캐시를 켠 채 — 새 회차는 커밋 뒤 목록에 나오고, 단계는 꺼낼 때 지금 시각으로, 응답에 Cache-Control. */
@OrderIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "nova.read-cache.enabled=true")
@Import(DrawReadCacheApiTest.Redis.class)
class DrawReadCacheApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Redis {
        @Bean
        DynamicPropertyRegistrar redisProperties() {
            return registry -> {
                registry.add("spring.data.redis.host", REDIS::getHost);
                registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
                registry.add("spring.data.redis.timeout", () -> "300ms");
            };
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redis;
    @Autowired DrawCampaignStore campaigns;
    @Autowired PlatformTransactionManager transactionManager;

    OrderFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        Set<String> keys = redis.keys("order:cache:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("상세는 두 번째부터 캐시 — DB 를 직접 고쳐도 옛 값이고, data 는 첫 응답과 글자가 같다")
    void detailIsServedFromCache() throws Exception {
        UUID drawId = create("첫 회차", Instant.now().minusSeconds(60), Instant.now().plus(Duration.ofDays(1)));
        String first = data(mockMvc.perform(get("/api/v1/draws/{id}", drawId)).andExpect(status().isOk())).toString();
        jdbcTemplate.update("UPDATE draw_campaigns SET title = '직접 고침' WHERE id = ?", (Object) OrderFixtures.bytes(drawId));

        String second = data(mockMvc.perform(get("/api/v1/draws/{id}", drawId)).andExpect(status().isOk())).toString();
        assertThat(second).isEqualTo(first);
        assertThat(JSON.readTree(second).get("title").asString()).isEqualTo("첫 회차");
    }

    @Test
    @DisplayName("새 회차를 만들면(JPA) 커밋 뒤 세대가 올라 캐시된 목록에 다음 조회부터 나온다")
    void createdDrawAppearsInCachedList() throws Exception {
        create("하나", Instant.now().minusSeconds(60), Instant.now().plus(Duration.ofDays(1)));
        List<String> before = titles();
        assertThat(before).contains("하나");

        create("둘", Instant.now().minusSeconds(60), Instant.now().plus(Duration.ofDays(1)));

        assertThat(titles()).contains("하나", "둘");
        assertThat(redis.opsForValue().get("order:cache:gen:draws")).isNotNull();
    }

    @Test
    @DisplayName("단계는 꺼낼 때 지금 시각으로 — 캐시된 회차도 시작 시각이 지나면 OPEN")
    void phaseIsRecomputed() throws Exception {
        Instant opensAt = Instant.now().plusMillis(1500);
        UUID drawId = create("곧 시작", opensAt, opensAt.plus(Duration.ofDays(1)));
        assertThat(data(mockMvc.perform(get("/api/v1/draws/{id}", drawId))).get("phase").asString()).isEqualTo("SCHEDULED");
        jdbcTemplate.update("UPDATE draw_campaigns SET title = '직접 고침' WHERE id = ?", (Object) OrderFixtures.bytes(drawId));

        Thread.sleep(Math.max(0, Duration.between(Instant.now(), opensAt).toMillis()) + 200);

        JsonNode detail = data(mockMvc.perform(get("/api/v1/draws/{id}", drawId)));
        assertThat(detail.get("title").asString()).as("캐시에서 꺼냈다").isEqualTo("곧 시작");
        assertThat(detail.get("phase").asString()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("목록 · 상세는 Cache-Control public, max-age=10 — 없는 회차(404)는 no-store")
    void publicResponsesCarryCacheControl() throws Exception {
        UUID drawId = create("헤더", Instant.now().minusSeconds(60), Instant.now().plus(Duration.ofDays(1)));
        mockMvc.perform(get("/api/v1/draws")).andExpect(header().string("Cache-Control", "max-age=10, public"));
        mockMvc.perform(get("/api/v1/draws/{id}", drawId)).andExpect(header().string("Cache-Control", "max-age=10, public"));
        mockMvc.perform(get("/api/v1/draws/{id}", UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    @DisplayName("Redis 가 멈춰도 목록 · 상세는 DB 로 200")
    void redisDownServesFromDatabase() throws Exception {
        UUID drawId = create("장애", Instant.now().minusSeconds(60), Instant.now().plus(Duration.ofDays(1)));
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertThat(data(mockMvc.perform(get("/api/v1/draws/{id}", drawId)).andExpect(status().isOk())).get("title").asString())
                    .isEqualTo("장애");
            mockMvc.perform(get("/api/v1/draws")).andExpect(status().isOk());
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    /** 관리자 만들기와 같은 저장소(JPA)로 회차를 넣는다 — 증정품 확보(재고)는 이 시험이 보지 않는다. */
    private UUID create(String title, Instant opensAt, Instant closesAt) {
        OrderFixtures.StockProduct gift = fixtures.inStockProduct(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> campaigns.insert(new NewDrawCampaign(UUID.randomUUID().toString(), gift.productId(), gift.optionIds().getFirst(),
                title, "증정품", "기본", null, new BigDecimal("100"), 1, opensAt, closesAt)).id());
    }

    private List<String> titles() throws Exception {
        List<String> titles = new ArrayList<>();
        data(mockMvc.perform(get("/api/v1/draws").param("size", "100")).andExpect(status().isOk())).get("items")
                .forEach(item -> titles.add(item.get("title").asString()));
        return titles;
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }
}
