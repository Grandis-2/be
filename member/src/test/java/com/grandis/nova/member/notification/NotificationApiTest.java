package com.grandis.nova.member.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.common.web.RequestIdFilter;
import com.grandis.nova.member.support.MemberIntegrationTest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

/**
 * 내 알림 API — 목록(최신순 · 커서) · 안 읽은 수 · 하나 읽음 · 모두 읽음. 알림은 소비기의 처리 함수로 넣는다(실제 MySQL).
 * 회원 행은 만들지 않는다 — 리졸버는 토큰의 회원 id 만 보고, 알림 표는 회원 표를 잇지 않는다.
 */
@MemberIntegrationTest
@DisplayName("내 알림 API — /api/v1/notifications (실제 MySQL)")
class NotificationApiTest {

    static final String PATH = "/api/v1/notifications";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired JwtTokenProvider provider;
    @Autowired NotificationReceiver receiver;
    @Autowired JdbcTemplate jdbc;

    MockMvc mvc;
    UUID me;
    UUID other;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter, springSecurityFilterChain).build();
        me = UUID.randomUUID();
        other = UUID.randomUUID();
    }

    @Test
    @DisplayName("항목 칸 이름은 notificationId · kind · title · message · target{type,id} · read · createdAt 그대로다")
    void itemShape() throws Exception {
        NotificationMessages message = new NotificationMessages(me).occurredAt("2026-10-15T03:00:00.123456Z");
        receiver.receive(message.body());

        JsonNode page = data(as(me, get(PATH)));

        assertThat(page.propertyNames()).containsExactlyInAnyOrder("items", "nextCursor");
        JsonNode item = page.get("items").get(0);
        assertThat(item.propertyNames())
                .containsExactlyInAnyOrder("notificationId", "kind", "title", "message", "target", "read", "createdAt");
        assertThat(item.get("notificationId").asString()).isEqualTo(idOf(message.eventId, me).toString());
        assertThat(item.get("kind").asString()).isEqualTo(message.kind);
        assertThat(item.get("title").asString()).isEqualTo("사전예약 등록 완료");
        assertThat(item.get("message").asString()).isEqualTo("사전예약 등록이 완료됐어요. 24시간 안에 결제해 주세요.");
        assertThat(item.get("target").propertyNames()).containsExactlyInAnyOrder("type", "id");
        assertThat(item.get("target").get("type").asString()).isEqualTo("PREORDER");
        assertThat(item.get("target").get("id").asString()).isEqualTo("3c0f6a52-6d1b-4f7e-9b2a-0e5d8c1f4a77");
        assertThat(item.get("read").isBoolean()).isTrue();
        assertThat(item.get("read").asBoolean()).isFalse();
        assertThat(item.get("createdAt").asString()).isEqualTo("2026-10-15T03:00:00.123456Z");
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("최신순 커서 페이징 — 같은 시각은 id 로 가르고, 쪽 경계에서 새거나 겹치지 않으며, 남의 알림은 섞이지 않는다")
    void listsNewestFirstWithCursor() throws Exception {
        List<String> expected = new ArrayList<>();
        // 오래된 것부터 넣는다. 같은 시각 둘은 나중에 넣은 쪽(id 가 큰 쪽)이 먼저 나온다
        for (String at : List.of("2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", "2026-10-03T00:00:00Z", "2026-10-03T00:00:00Z",
                "2026-10-04T00:00:00Z")) {
            NotificationMessages message = new NotificationMessages(me, other).occurredAt(at);
            receiver.receive(message.body());
            expected.addFirst(idOf(message.eventId, me).toString());
        }
        receiver.receive(new NotificationMessages(other).occurredAt("2026-10-05T00:00:00Z").body());

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            MockHttpServletRequestBuilder request = get(PATH).param("size", "2");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode page = data(as(me, request));
            page.get("items").forEach(item -> seen.add(item.get("notificationId").asString()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
            pages++;
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    @DisplayName("size 는 1~50, 깨진 커서는 400")
    void rejectsBadPaging() throws Exception {
        for (String size : List.of("0", "51")) {
            mvc.perform(as(me, get(PATH).param("size", size))).andExpect(status().isBadRequest());
        }
        mvc.perform(as(me, get(PATH).param("size", "50"))).andExpect(status().isOk());
        mvc.perform(as(me, get(PATH).param("cursor", "%%%"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("안 읽은 수 — 내 안 읽은 것만 센다. 하나 읽으면 줄고, 모두 읽으면 0, 남의 것은 그대로")
    void unreadCountAndReadAll() throws Exception {
        for (int i = 0; i < 3; i++) {
            receiver.receive(new NotificationMessages(me, other).body());
        }
        assertThat(unread(me)).isEqualTo(3);

        UUID first = UUID.fromString(data(as(me, get(PATH))).get("items").get(0).get("notificationId").asString());
        mvc.perform(as(me, patch(PATH + "/" + first + "/read"))).andExpect(status().isNoContent());
        assertThat(unread(me)).isEqualTo(2);

        mvc.perform(as(me, post(PATH + "/read-all"))).andExpect(status().isNoContent());
        assertThat(unread(me)).isZero();
        assertThat(unread(other)).isEqualTo(3);
        assertThat(data(as(me, get(PATH))).get("items").valueStream().map(item -> item.get("read").asBoolean())).containsOnly(true);

        mvc.perform(as(me, post(PATH + "/read-all"))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("하나 읽음 — 다시 눌러도 처음 읽은 시각을 덮지 않는다. 남의 알림 · 없는 id 는 같은 404 이고 남의 알림은 그대로")
    void markOneRead() throws Exception {
        NotificationMessages message = new NotificationMessages(me, other);
        receiver.receive(message.body());
        UUID mine = idOf(message.eventId, me);
        UUID theirs = idOf(message.eventId, other);

        mvc.perform(as(me, patch(PATH + "/" + mine + "/read"))).andExpect(status().isNoContent());
        LocalDateTime firstRead = readAt(mine);
        assertThat(firstRead).isNotNull();
        mvc.perform(as(me, patch(PATH + "/" + mine + "/read"))).andExpect(status().isNoContent());
        assertThat(readAt(mine)).isEqualTo(firstRead);
        assertThat(data(as(me, get(PATH))).get("items").get(0).get("read").asBoolean()).isTrue();

        mvc.perform(as(me, patch(PATH + "/" + theirs + "/read"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(as(me, patch(PATH + "/" + UUID.randomUUID() + "/read"))).andExpect(status().isNotFound());
        assertThat(readAt(theirs)).isNull();
    }

    @Test
    @DisplayName("회원 전용 — 토큰 없으면 401, ADMIN 토큰은 403")
    void customerOnly() throws Exception {
        mvc.perform(get(PATH + "/unread-count")).andExpect(status().isUnauthorized());
        String admin = provider.create("admin", Role.ADMIN, UUID.randomUUID(), TokenType.ACCESS);
        mvc.perform(get(PATH + "/unread-count").header(BearerTokens.HEADER, BearerTokens.value(admin)))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder as(UUID customerId, MockHttpServletRequestBuilder request) {
        String token = provider.create(customerId.toString(), Role.USER, UUID.randomUUID(), TokenType.ACCESS);
        return request.header(BearerTokens.HEADER, BearerTokens.value(token));
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("data");
    }

    private long unread(UUID customerId) throws Exception {
        JsonNode data = data(as(customerId, get(PATH + "/unread-count")));
        assertThat(data.propertyNames()).containsExactly("count");
        return data.get("count").asLong();
    }

    private UUID idOf(String eventId, UUID customerId) {
        return UuidBinary.fromBytes(jdbc.queryForObject("SELECT id FROM notifications WHERE event_id = ? AND customer_id = ?",
                byte[].class, eventId, UuidBinary.toBytes(customerId)));
    }

    private LocalDateTime readAt(UUID id) {
        return jdbc.queryForObject("SELECT read_at FROM notifications WHERE id = ?", LocalDateTime.class, (Object) UuidBinary.toBytes(id));
    }
}
