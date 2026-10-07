package com.grandis.nova.catalog.option;

import com.grandis.nova.catalog.option.ProductOptions.Pick;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * 옵션 하나가 축마다 고른 값의 묶음. 조합 키 · 표시명 · 필터 JSON 이 전부 여기서 나온다 — 호출자가 따로 만들면 서로 어긋날 자리가 열린다.
 * 목록 필터는 filter_attributes 를 읽고 상세 응답은 조합 키로 고른 값을 찾는다 — 둘이 어긋나면 필터에 걸린 옵션과 화면이 보이는 값이 다르다.
 *
 * 고른 값은 상품의 옵션 문서({@link ProductOptions})의 축 순서로 받는다(호출자가 문서의 축을 차례로 돌며 만든다 — {@link ProductOptions#picksOf}).
 * 축이 없는 상품은 {@link #none} — 선택이 없으니 표시명은 받은 그대로고 키는 {@link #STANDALONE_KEY} 다.
 */
public final class OptionCombination {

    /** 축이 없는 상품의 옵션이 갖는 조합 키. 값 id 로 만든 키는 비지 않아 겹치지 않는다. */
    public static final String STANDALONE_KEY = "";
    static final String KEY_SEPARATOR = "-";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TITLE_SEPARATOR = " / ";
    /**
     * 값 id 의 키 순서 — 짧은 것 먼저, 같으면 문자열 순. 앞자리 0 이 없는 숫자 문자열(표에서 옮겨 온 옛 id)은 숫자 순과 같아 옛 키("9-12")가
     * 그대로 맞고, 새 32자 id 는 문자열 순이다.
     */
    private static final Comparator<String> KEY_ORDER = Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder());

    private final Long productId;
    /** 축 순. 축이 없는 상품이면 비어 있다. */
    private final List<Pick> picks;
    /** 축이 없는 상품의 표시명. 선택이 있으면 null 이고 값에서 만든다. */
    private final String standaloneTitle;

    private OptionCombination(Long productId, List<Pick> picks, String standaloneTitle) {
        this.productId = productId;
        this.picks = List.copyOf(picks);
        this.standaloneTitle = standaloneTitle;
    }

    /** 축이 없는 상품의 유일한 옵션. 상품당 하나는 DB UNIQUE(product_id, combination_key = '') 가 지킨다. */
    public static OptionCombination none(Long productId, String title) {
        return new OptionCombination(productId, List.of(), titleOf(List.of(), title));
    }

    /**
     * 옵션 표시명 규칙 한 곳 — 축 순서의 값 표시명을 " / " 로 잇고(블랙 / 256GB), 값이 없으면 상품 표시명의 정규화값이다.
     * 저장 전에 길이를 재는 검증기도 이 함수를 쓴다. 따로 만들면 검사와 저장이 어긋난다.
     */
    public static String titleOf(List<String> displayValues, String productTitle) {
        if (displayValues.isEmpty()) {
            return OptionText.normalize(productTitle);
        }
        return String.join(TITLE_SEPARATOR, displayValues);
    }

    /** @param picks 축 순서. 같은 축이 두 번이면 거절한다 */
    public static OptionCombination of(Long productId, List<Pick> picks) {
        if (picks == null || picks.isEmpty()) {
            throw new IllegalArgumentException("a combination needs at least one pick");
        }
        Set<String> axisKeys = new HashSet<>();
        for (Pick pick : picks) {
            if (!axisKeys.add(pick.axis().key())) {
                throw new IllegalArgumentException("axis " + pick.axis().key() + " picked twice");
            }
            if (pick.axis().valueById(pick.value().id()).isEmpty()) {
                throw new IllegalArgumentException("value " + pick.value().id() + " is not a value of axis " + pick.axis().key());
            }
        }
        return new OptionCombination(productId, picks, null);
    }

    /** 저장된 조합 키를 값 id 로 되돌린다. 축 없는 옵션('')과 키가 없는 행(다른 모듈 픽스처)은 빈 목록이다. */
    public static List<String> valueIdsOf(String combinationKey) {
        if (combinationKey == null || combinationKey.isEmpty()) {
            return List.of();
        }
        return List.of(combinationKey.split(KEY_SEPARATOR));
    }

    public boolean isStandalone() {
        return picks.isEmpty();
    }

    public Long getProductId() {
        return productId;
    }

    public List<Pick> getPicks() {
        return picks;
    }

    /** 이 조합이 상품의 축 전부에 값을 갖는가. 축이 나중에 더해지면 기존 옵션은 여기서 거짓이 된다. */
    public boolean covers(List<ProductOptions.Axis> productAxes) {
        Set<String> picked = picks.stream().map(pick -> pick.axis().key()).collect(Collectors.toSet());
        return productAxes.stream().map(ProductOptions.Axis::key).allMatch(picked::contains);
    }

    /** 값 id 를 {@link #KEY_ORDER} 로 정렬해 '-' 로 잇는다. 같은 값 집합이면 같은 키다. DB 가 (product_id, key) UNIQUE 로 같은 조합을 막는다. */
    public String combinationKey() {
        if (isStandalone()) {
            return STANDALONE_KEY;
        }
        List<String> ids = new ArrayList<>(picks.stream().map(pick -> pick.value().id()).toList());
        ids.sort(KEY_ORDER);
        return String.join(KEY_SEPARATOR, ids);
    }

    /** 축 순서대로 값 표시명을 " / " 로 잇는다(블랙 / 256GB). 축이 없는 상품이면 받은 표시명이다. */
    public String title() {
        if (isStandalone()) {
            return standaloneTitle;
        }
        return titleOf(picks.stream().map(pick -> pick.value().value()).toList(), null);
    }

    /** 목록 필터 축(color · storage)의 정규화값 JSON. 없으면 null. 옵션 상세 응답의 filterAttributes 다. */
    public String filterAttributes() {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Pick pick : picks) {
            if (pick.axis().isFilterAxis()) {
                attributes.put(pick.axis().key(), pick.value().normalized());
            }
        }
        return attributes.isEmpty() ? null : JSON.writeValueAsString(attributes);
    }
}
