package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.common.web.RequestIdFilter;
import com.grandis.nova.member.support.MemberIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 알림 API — 목록 · 안 읽은 수 · 읽음. 알림은 Receiver 로 넣는다(실제 MySQL). 이름 조회는 상대가 없어 실패하고 이름은 null 이다. */
@MemberIntegrationTest
@DisplayName("관리자 알림 API — /api/v1/admin/notifications (실제 MySQL)")
class AdminAlertApiTest {

    static final String PATH = "/api/v1/admin/notifications";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired JwtTokenProvider provider;
    @Autowired GrafanaAlertReceiver grafana;
    @Autowired AdminAlertEventReceiver events;
    @Autowired JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter, springSecurityFilterChain).build();
        jdbc.update("DELETE FROM admin_alerts");
    }

    @Test
    @DisplayName("항목 칸은 요청서 모양에 raisedAt · resolvable 을 더한 것이다. 대상이 없으면 target.kind=NONE · id=null")
    void itemShape() throws Exception {
        grafana.receive(AdminAlertMessages.Grafana.firing("REGISTER_FAILED", "2026-10-26T05:02:11Z").metric("deadLetters", "860").body());

        JsonNode page = data(admin(get(PATH)));

        JsonNode item = page.get("items").get(0);
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("notificationId", "type", "audience", "severity", "status", "target",
                "metrics", "resolvable", "firstOccurredAt", "lastUpdatedAt", "raisedAt", "resolvedAt", "readAt");
        assertThat(item.get("type").asString()).isEqualTo("REGISTER_FAILED");
        assertThat(item.get("audience").asString()).isEqualTo("ADMIN");
        assertThat(item.get("severity").asString()).isEqualTo("URGENT");
        assertThat(item.get("status").asString()).isEqualTo("OPEN");
        assertThat(item.get("target").get("kind").asString()).isEqualTo("NONE");
        assertThat(item.get("target").get("id").isNull()).isTrue();
        assertThat(item.get("target").get("name").isNull()).isTrue();
        assertThat(item.get("metrics").get("deadLetters").asInt()).isEqualTo(860);
        assertThat(item.get("resolvable").asBoolean()).isTrue();
        assertThat(item.get("firstOccurredAt").asString()).isEqualTo("2026-10-26T05:02:11Z");
        assertThat(item.get("resolvedAt").isNull()).isTrue();
        assertThat(item.get("readAt").isNull()).isTrue();
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("한 번 오는 소식은 RESOLVED · resolvable=false, 상품 대상은 kind · id 를 그대로 준다")
    void onceItem() throws Exception {
        UUID product = UUID.randomUUID();
        events.receive(AdminAlertMessages.Event.preorder("PREORDER_OPENED", product, UUID.randomUUID(), "2026-10-26T04:00:00Z").body());

        JsonNode item = data(admin(get(PATH))).get("items").get(0);

        assertThat(item.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(item.get("resolvable").asBoolean()).isFalse();
        assertThat(item.get("resolvedAt").asString()).isEqualTo("2026-10-26T04:00:00Z");
        assertThat(item.get("target").get("kind").asString()).isEqualTo("PRODUCT");
        assertThat(item.get("target").get("id").asString()).isEqualTo(product.toString());
    }

    @Test
    @DisplayName("audience · status · severity · unreadOnly 로 거르고, raisedAt 내림차순 커서로 새거나 겹치지 않게 넘긴다")
    void filtersAndPages() throws Exception {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            grafana.receive(AdminAlertMessages.Grafana.firing("SERVICE_ERROR_SPIKE", "2026-10-26T05:0" + i + ":00Z")
                    .target("SERVICE", "svc-" + i).body());
            expected.addFirst("svc-" + i);
        }
        grafana.receive(AdminAlertMessages.Grafana.firing("SHIPPING_BACKLOG", "2026-10-26T05:00:00Z").metric("total", "58").body());

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            MockHttpServletRequestBuilder request = get(PATH).param("audience", "DEVELOPER").param("size", "2");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode page = data(admin(request));
            page.get("items").forEach(item -> seen.add(item.get("target").get("id").asString()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
        } while (cursor != null);
        assertThat(seen).containsExactlyElementsOf(expected);

        assertThat(data(admin(get(PATH).param("severity", "INFO"))).get("items")).hasSize(1);
        assertThat(data(admin(get(PATH).param("severity", "URGENT,CHECK"))).get("items")).hasSize(5);
        assertThat(data(admin(get(PATH).param("status", "RESOLVED"))).get("items")).isEmpty();

        mvc.perform(admin(post(PATH + "/read-all").param("audience", "DEVELOPER"))).andExpect(status().isNoContent());
        assertThat(data(admin(get(PATH).param("unreadOnly", "true"))).get("items")).hasSize(1);
    }

    @Test
    @DisplayName("안 읽은 수는 받는 사람 → 심각도별이고 둘 다 늘 있다. 하나 읽음은 처음 시각을 지키고, 없는 id 는 404")
    void unreadAndRead() throws Exception {
        grafana.receive(AdminAlertMessages.Grafana.firing("SERVICE_INCIDENT", "2026-10-26T05:00:00Z").target("SERVICE", "order").body());

        JsonNode counts = data(admin(get(PATH + "/unread-count")));
        assertThat(counts.propertyNames()).containsExactly("ADMIN", "DEVELOPER");
        assertThat(counts.get("ADMIN").propertyNames()).containsExactly("urgent", "check", "info");
        assertThat(counts.get("ADMIN").get("urgent").asLong()).isEqualTo(1);
        assertThat(counts.get("DEVELOPER").get("urgent").asLong()).isEqualTo(1);

        String id = data(admin(get(PATH).param("audience", "ADMIN"))).get("items").get(0).get("notificationId").asString();
        mvc.perform(admin(patch(PATH + "/" + id + "/read"))).andExpect(status().isNoContent());
        Object first = jdbc.queryForObject("SELECT read_at FROM admin_alerts WHERE audience = 'ADMIN'", Object.class);
        mvc.perform(admin(patch(PATH + "/" + id + "/read"))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT read_at FROM admin_alerts WHERE audience = 'ADMIN'", Object.class)).isEqualTo(first);

        counts = data(admin(get(PATH + "/unread-count")));
        assertThat(counts.get("ADMIN").get("urgent").asLong()).isZero();
        assertThat(counts.get("DEVELOPER").get("urgent").asLong()).isEqualTo(1);

        mvc.perform(admin(patch(PATH + "/" + UUID.randomUUID() + "/read"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("깨진 커서 · 범위 밖 size 는 400")
    void badRequests() throws Exception {
        mvc.perform(admin(get(PATH).param("cursor", "%%%"))).andExpect(status().isBadRequest());
        mvc.perform(admin(get(PATH).param("size", "51"))).andExpect(status().isBadRequest());
        mvc.perform(admin(get(PATH).param("audience", "SOMEONE"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("severity 의 빈 요소(\"URGENT,\" · severity=URGENT&severity=)는 500 이 아니라 400 이다")
    void blankSeverityIs400() throws Exception {
        mvc.perform(admin(get(PATH).param("severity", "URGENT,")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(admin(get(PATH).param("severity", "URGENT").param("severity", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("토큰이 없으면 401, 회원(USER) 토큰은 403")
    void adminOnly() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        String user = provider.create(UUID.randomUUID().toString(), Role.USER, UUID.randomUUID(), TokenType.ACCESS);
        mvc.perform(get(PATH + "/unread-count").header(BearerTokens.HEADER, BearerTokens.value(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        String token = provider.create("admin", Role.ADMIN, UUID.randomUUID(), TokenType.ACCESS);
        return request.header(BearerTokens.HEADER, BearerTokens.value(token));
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("data");
    }
}
