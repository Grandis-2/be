package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실행 순서에 따라 다른 시험이 넣은 카테고리가 같은 컨테이너에 남아 있을 수 있으므로 전체 목록이 아니라 이 시험이 만든 id 로 찾아 본다.
 * 픽스처 값은 기대 순서가 생성 순 · 이름 오름 · 내림 · 표시 순서 내림 어느 것과도 다르게 고른다 — 원소가 둘이거나 이름이 같으면 정렬 돌연변이를 못 잡는다.
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
    @DisplayName("상위는 최상위에, 하위는 상위의 children 에 표시 순서(sort_order, 같으면 생성 순)로 온다. 하위 없는 상위는 빈 children, 순서 · code 칸은 싣지 않는다")
    void returnsTwoLevelTree() throws Exception {
        // 생성 순: 모바일 · PC · 액세서리, 표시 순서 2 · 0 · 1 → PC · 액세서리 · 모바일.
        // 생성 순 · 이름 오름(PC · 모바일 · 액세서리) · 이름 내림 · 표시 순서 내림 어느 것과도 다르다
        UUID mobile = fixtures.category("모바일", 2);
        UUID pc = fixtures.category("PC", 0);
        UUID accessory = fixtures.category("액세서리", 1);
        // 하위 생성 순: 삼성 · Apple · 기타, 표시 순서 1 · 0 · 1 → Apple · 삼성 · 기타. 삼성 · 기타는 순서가 같아 생성 순으로 가른다 —
        // 이름 오름(Apple · 기타 · 삼성)이나 같은 순서끼리 생성 역순이면 다르게 나온다
        UUID samsung = fixtures.childCategory(mobile, "삼성", 1);
        UUID apple = fixtures.childCategory(mobile, "Apple", 0);
        UUID etc = fixtures.childCategory(mobile, "기타", 1);

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
        assertThat(find(mobileNode.get("children"), samsung).get("parentId").asString()).isEqualTo(mobile.toString());
        assertThat(find(mobileNode.get("children"), apple).get("name").asString()).isEqualTo("Apple");

        assertThat(find(items, pc).get("children")).isEmpty();
        assertThat(find(items, accessory).get("children")).isEmpty();
        // 하위는 최상위에 나오지 않고, 최상위는 한 번씩만, 순서는 표시 순서
        List<UUID> topIds = idsOf(items);
        assertThat(topIds).doesNotHaveDuplicates().doesNotContain(samsung, apple, etc);
        assertThat(topIds.stream().filter(id -> id.equals(mobile) || id.equals(pc) || id.equals(accessory)).toList())
                .containsExactly(pc, accessory, mobile);
    }

    @Test
    @DisplayName("노드마다 optionAxes(기본 옵션 축)가 DB 에 넣은 순서 그대로 온다 — 넣지 않은 카테고리(상위 포함)는 빈 배열")
    void nodesCarryDefaultOptionAxes() throws Exception {
        UUID mobile = fixtures.category();
        UUID tablet = fixtures.childCategory(mobile, "태블릿");
        UUID foldable = fixtures.childCategory(mobile, "폴더블");
        // 키 사전순(color · connectivity · storage)과 다른 순서 — 화면이 그리는 순서다
        jdbcTemplate.update("""
                UPDATE categories SET option_axes = JSON_ARRAY(JSON_OBJECT('key', 'storage', 'label', '저장 용량'),
                       JSON_OBJECT('key', 'color', 'label', '색상'), JSON_OBJECT('key', 'connectivity', 'label', '연결')) WHERE id = ?""",
                (Object) UuidBinary.toBytes(tablet));

        JsonNode items = itemsOf(mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        JsonNode mobileNode = find(items, mobile);
        assertThat(mobileNode.get("optionAxes").isArray()).isTrue();
        assertThat(mobileNode.get("optionAxes")).isEmpty();
        JsonNode axes = find(mobileNode.get("children"), tablet).get("optionAxes");
        assertThat(axes).hasSize(3);
        assertThat(axes.get(0).get("key").asString()).isEqualTo("storage");
        assertThat(axes.get(0).get("label").asString()).isEqualTo("저장 용량");
        assertThat(axes.get(1).get("key").asString()).isEqualTo("color");
        assertThat(axes.get(2).get("key").asString()).isEqualTo("connectivity");
        assertThat(find(mobileNode.get("children"), foldable).get("optionAxes")).as("기본값 []").isEmpty();
    }

    @Test
    @DisplayName("깊이 3 이상인 행은 오류 없이 응답에서 빠진다 — 막는 자리는 시드다")
    void grandchildIsDroppedWithoutError() throws Exception {
        UUID root = fixtures.category();
        UUID child = fixtures.childCategory(root, "삼성");
        UUID grandchild = fixtures.childCategory(child, "갤럭시");

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
        UUID self = fixtures.category();
        jdbcTemplate.update("UPDATE categories SET parent_id = id WHERE id = ?", UuidBinary.toBytes(self));
        UUID first = fixtures.category();
        UUID second = fixtures.childCategory(first, "둘째");
        jdbcTemplate.update("UPDATE categories SET parent_id = ? WHERE id = ?", UuidBinary.toBytes(second), UuidBinary.toBytes(first));

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

    private static JsonNode find(JsonNode nodes, UUID categoryId) {
        for (JsonNode node : nodes) {
            if (node.get("categoryId").asString().equals(categoryId.toString())) {
                return node;
            }
        }
        throw new AssertionError("category " + categoryId + " not in " + nodes);
    }

    /** 트리의 모든 노드 id(깊이 무관). */
    private static List<UUID> allIdsOf(JsonNode nodes) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode node : nodes) {
            ids.add(UUID.fromString(node.get("categoryId").asString()));
            ids.addAll(allIdsOf(node.get("children")));
        }
        return ids;
    }

    private static List<UUID> idsOf(JsonNode nodes) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode node : nodes) {
            ids.add(UUID.fromString(node.get("categoryId").asString()));
        }
        return ids;
    }
}
