package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실행 순서에 따라 다른 시험이 넣은 카테고리가 같은 컨테이너에 남아 있을 수 있으므로 전체 목록이 아니라 이 시험이 만든 id 로 찾아 본다.
 * 픽스처 값은 기대 순서가 id 순 · 이름 오름 · 내림 · 표시 순서 내림 어느 것과도 다르게 고른다 — 원소가 둘이거나 이름이 같으면 정렬 돌연변이를 못 잡는다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
class CategoryApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    @DisplayName("상위는 최상위에, 하위는 상위의 children 에 표시 순서(sort_order, 같으면 id)로 온다. 하위 없는 상위는 빈 children, 순서 · code 칸은 싣지 않는다")
    void returnsTwoLevelTree() throws Exception {
        // 생성(id) 순: 모바일 · PC · 액세서리, 표시 순서 2 · 0 · 1 → PC · 액세서리 · 모바일.
        // id 순 · 이름 오름(PC · 모바일 · 액세서리) · 이름 내림 · 표시 순서 내림 어느 것과도 다르다
        Long mobile = fixtures.category("모바일", 2);
        Long pc = fixtures.category("PC", 0);
        Long accessory = fixtures.category("액세서리", 1);
        // 하위 생성 순: 삼성 · Apple · 기타, 표시 순서 1 · 0 · 1 → Apple · 삼성 · 기타. 삼성 · 기타는 순서가 같아 id 오름으로 가른다 —
        // 이름 오름(Apple · 기타 · 삼성)이나 같은 순서끼리 id 내림이면 다르게 나온다
        Long samsung = fixtures.childCategory(mobile, "삼성", 1);
        Long apple = fixtures.childCategory(mobile, "Apple", 0);
        Long etc = fixtures.childCategory(mobile, "기타", 1);

        JsonNode items = itemsOf(mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isArray())
                .andReturn().getResponse().getContentAsString());

        JsonNode mobileNode = find(items, mobile);
        assertThat(mobileNode.get("parentId").isNull()).isTrue();
        assertThat(mobileNode.get("name").asString()).isEqualTo("모바일");
        assertThat(mobileNode.has("code")).as("code 칸은 없앴다").isFalse();
        assertThat(mobileNode.has("sortOrder")).as("배열 순서가 곧 표시 순서 — 칸은 싣지 않는다(명세)").isFalse();
        assertThat(idsOf(mobileNode.get("children"))).containsExactly(apple, samsung, etc);
        assertThat(find(mobileNode.get("children"), samsung).get("parentId").asLong()).isEqualTo(mobile);
        assertThat(find(mobileNode.get("children"), apple).get("name").asString()).isEqualTo("Apple");

        assertThat(find(items, pc).get("children")).isEmpty();
        assertThat(find(items, accessory).get("children")).isEmpty();
        // 하위는 최상위에 나오지 않고, 최상위는 한 번씩만, 순서는 표시 순서
        List<Long> topIds = idsOf(items);
        assertThat(topIds).doesNotHaveDuplicates().doesNotContain(samsung, apple, etc);
        assertThat(topIds.stream().filter(id -> id.equals(mobile) || id.equals(pc) || id.equals(accessory)).toList())
                .containsExactly(pc, accessory, mobile);
    }

    @Test
    @DisplayName("깊이 3 이상인 행은 오류 없이 응답에서 빠진다 — 막는 자리는 시드다")
    void grandchildIsDroppedWithoutError() throws Exception {
        Long root = fixtures.category();
        Long child = fixtures.childCategory(root, "삼성");
        Long grandchild = fixtures.childCategory(child, "갤럭시");

        JsonNode items = itemsOf(mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(idsOf(items)).contains(root).doesNotContain(child, grandchild);
        JsonNode children = find(items, root).get("children");
        assertThat(idsOf(children)).containsExactly(child);
        assertThat(find(children, child).get("children")).isEmpty();
        assertThat(allIdsOf(items)).contains(root, child).doesNotContain(grandchild);
    }

    @Test
    @DisplayName("자기 참조 · 서로 가리키는 순환 행도 오류 없이 응답에서 빠진다")
    void cyclesAreDroppedWithoutError() throws Exception {
        Long self = fixtures.category();
        jdbcTemplate.update("UPDATE categories SET parent_id = id WHERE id = ?", self);
        Long first = fixtures.category();
        Long second = fixtures.childCategory(first, "둘째");
        jdbcTemplate.update("UPDATE categories SET parent_id = ? WHERE id = ?", second, first);

        JsonNode items = itemsOf(mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(allIdsOf(items)).doesNotContain(self, first, second);
    }

    @Test
    @DisplayName("로그인 없이 볼 수 있다")
    void isPublic() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    private static JsonNode itemsOf(String body) {
        return JSON.readTree(body).at("/data/items");
    }

    private static JsonNode find(JsonNode nodes, Long categoryId) {
        for (JsonNode node : nodes) {
            if (node.get("categoryId").asLong() == categoryId) {
                return node;
            }
        }
        throw new AssertionError("category " + categoryId + " not in " + nodes);
    }

    /** 트리의 모든 노드 id(깊이 무관). */
    private static List<Long> allIdsOf(JsonNode nodes) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : nodes) {
            ids.add(node.get("categoryId").asLong());
            ids.addAll(allIdsOf(node.get("children")));
        }
        return ids;
    }

    private static List<Long> idsOf(JsonNode nodes) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : nodes) {
            ids.add(node.get("categoryId").asLong());
        }
        return ids;
    }
}
