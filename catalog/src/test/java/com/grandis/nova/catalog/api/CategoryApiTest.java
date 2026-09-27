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
 * 픽스처 값은 id 순이 이름 오름 · 내림, code 오름 · 내림 어느 것과도 다르게 고른다 — 원소가 둘이거나 이름이 같으면 정렬 돌연변이를 못 잡는다.
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
    @DisplayName("상위는 최상위에, 하위는 상위의 children 에 id 순으로 온다. 하위 없는 상위는 빈 children")
    void returnsTwoLevelTree() throws Exception {
        // 생성 순: 모바일(m-) · PC(b-) · 액세서리(z-). 이름 오름차순은 PC · 모바일 · 액세서리, code 오름차순은 b · m · z — 둘 다 생성 순과 다르다
        Long mobile = fixtures.category("m-" + ShopFixtures.unique(), "모바일");
        Long pc = fixtures.category("b-" + ShopFixtures.unique(), "PC");
        Long accessory = fixtures.category("z-" + ShopFixtures.unique(), "액세서리");
        // 하위 생성 순: 삼성(s-) · Apple(a-) · 기타(k-). 이름 · code 어느 정렬도 생성 순과 다르다
        Long samsung = fixtures.childCategory(mobile, "s-" + ShopFixtures.unique(), "삼성");
        Long apple = fixtures.childCategory(mobile, "a-" + ShopFixtures.unique(), "Apple");
        Long etc = fixtures.childCategory(mobile, "k-" + ShopFixtures.unique(), "기타");

        JsonNode items = itemsOf(mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isArray())
                .andReturn().getResponse().getContentAsString());

        JsonNode mobileNode = find(items, mobile);
        assertThat(mobileNode.get("parentId").isNull()).isTrue();
        assertThat(mobileNode.get("name").asString()).isEqualTo("모바일");
        assertThat(mobileNode.get("code").asString()).startsWith("m-");
        assertThat(idsOf(mobileNode.get("children"))).containsExactly(samsung, apple, etc);
        assertThat(find(mobileNode.get("children"), samsung).get("parentId").asLong()).isEqualTo(mobile);
        assertThat(find(mobileNode.get("children"), apple).get("name").asString()).isEqualTo("Apple");

        assertThat(find(items, pc).get("children")).isEmpty();
        assertThat(find(items, accessory).get("children")).isEmpty();
        // 하위는 최상위에 나오지 않고, 최상위는 한 번씩만, 순서는 id(생성 순)
        List<Long> topIds = idsOf(items);
        assertThat(topIds).doesNotHaveDuplicates().doesNotContain(samsung, apple, etc);
        assertThat(topIds.stream().filter(id -> id.equals(mobile) || id.equals(pc) || id.equals(accessory)).toList())
                .containsExactly(mobile, pc, accessory);
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
