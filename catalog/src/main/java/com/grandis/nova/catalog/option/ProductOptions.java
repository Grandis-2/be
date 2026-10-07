package com.grandis.nova.catalog.option;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * 상품의 옵션 정의 · 사진 — products.options(JSON) 한 칸. 축과 값 · 값별 추가금 · 색상 hex · 색상별 사진 · 기본 묶음 · 상세 영역이 여기 있다.
 * 배열 순서가 곧 표시 순서다(position 칸이 없다). 옵션(조합) 행은 {@code product_options} 에 그대로 있고, 고른 값은 조합 키(값 id)로 잇는다.
 *
 * <p>값 id 는 이름과 무관하게 고정이다 — 이름을 고쳐도 조합 키 · 사진 · 관리자 API 경로가 그대로다. 새 값은 32자 16진수({@link #newValueId}),
 * 표에서 옮겨 온 값은 옛 숫자 id 의 문자열이다. 둘 다 조합 키 구분자 '-' 를 갖지 않는다.
 *
 * <p>한 상품의 수정은 상품 행 잠금으로 줄 서므로 문서를 통째로 다시 써도 서로 덮지 않는다. 같은 축의 같은 값 · 대표 사진 하나는 앱이 지킨다.
 */
public record ProductOptions(List<Axis> axes, List<Image> defaultImages, List<Section> detailImages) {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final ProductOptions EMPTY = new ProductOptions(List.of(), List.of(), List.of());

    public ProductOptions {
        axes = axes == null ? List.of() : List.copyOf(axes);
        defaultImages = defaultImages == null ? List.of() : List.copyOf(defaultImages);
        detailImages = detailImages == null ? List.of() : List.copyOf(detailImages);
    }

    /** 축. key 는 소문자로 접은 키(color · storage · 관리자 입력). */
    public record Axis(String key, String label, List<Value> values) {
        public Axis {
            values = values == null ? List.of() : List.copyOf(values);
        }

        /** 파생 값이라 문서에 쓰지 않는다 — getter 꼴이라 그냥 두면 "filterAxis" 칸이 저장된다. */
        @JsonIgnore
        public boolean isFilterAxis() {
            return OptionText.isFilterAxis(key);
        }

        public Optional<Value> valueById(String id) {
            return values.stream().filter(value -> value.id().equals(id)).findFirst();
        }

        public Optional<Value> valueByNormalized(String normalized) {
            return values.stream().filter(value -> value.normalized().equals(normalized)).findFirst();
        }
    }

    /**
     * 값. value 는 표시값, normalized 는 비교 · 필터 키(용량은 숫자 + 대문자 단위), hex 는 색상 스와치(색상 축만, 없으면 null),
     * images 는 그 색상의 사진(색상 축만).
     */
    public record Value(String id, String value, String normalized, String hex, BigDecimal surcharge, List<Image> images) {
        public Value {
            images = images == null ? List.of() : List.copyOf(images);
            // 소수점 없는 표기로 — 1.5e3 을 그대로 쓰면 JSON 에 1.5E+3 으로 들어가 DB 가 실수(1500.0)로 담고 응답도 1500.0 이 된다. 금액 검사는 호출자가 먼저 한다
            surcharge = surcharge == null ? null : surcharge.setScale(0, RoundingMode.UNNECESSARY);
        }
    }

    public record Image(String url, boolean primary) {
    }

    /** 상세 콘텐츠 영역(상세정보 · 제품사양 …)의 이미지. */
    public record Section(String section, List<Image> images) {
        public Section {
            images = images == null ? List.of() : List.copyOf(images);
        }
    }

    /** 축과 그 축에서 고른 값. */
    public record Pick(Axis axis, Value value) {
    }

    public static ProductOptions parse(String json) {
        return json == null || json.isBlank() ? EMPTY : JSON.readValue(json, ProductOptions.class);
    }

    public String toJson() {
        return JSON.writeValueAsString(this);
    }

    public static String newValueId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public Optional<Axis> axis(String key) {
        return axes.stream().filter(axis -> axis.key().equals(key)).findFirst();
    }

    /** 값 id 로 그 값과 축을 찾는다. */
    public Optional<Pick> pickOf(String valueId) {
        for (Axis axis : axes) {
            Optional<Value> value = axis.valueById(valueId);
            if (value.isPresent()) {
                return Optional.of(new Pick(axis, value.get()));
            }
        }
        return Optional.empty();
    }

    /** 값 id 묶음을 축 순서의 고른 값으로. 문서에 없는 id 는 빠진다. */
    public List<Pick> picksOf(Collection<String> valueIds) {
        Set<String> ids = new HashSet<>(valueIds);
        List<Pick> picks = new ArrayList<>();
        for (Axis axis : axes) {
            for (Value value : axis.values()) {
                if (ids.contains(value.id())) {
                    picks.add(new Pick(axis, value));
                }
            }
        }
        return picks;
    }

    /** 축 하나를 바꾼 문서. */
    public ProductOptions withAxis(Axis replaced) {
        List<Axis> next = new ArrayList<>();
        for (Axis axis : axes) {
            next.add(axis.key().equals(replaced.key()) ? replaced : axis);
        }
        return new ProductOptions(next, defaultImages, detailImages);
    }

    /**
     * 목록 · 장바구니 · 리뷰 카드의 썸네일 — 첫 색상(관리자가 넣은 순서)의 첫 장, 색상 축이 없으면 기본 묶음의 첫 장. 첫 색상에 사진이 없으면 null 이다
     * (다음 색상으로 넘어가지 않는다). 대표 표시는 보지 않는다. 프론트 카드가 첫 색상을 골라 그 색상의 사진을 첫 장부터 보여 주는 것과 같은 규칙이다.
     */
    public String thumbnailUrl() {
        List<Image> first = axis(OptionText.COLOR)
                .map(color -> color.values().isEmpty() ? List.<Image>of() : color.values().getFirst().images())
                .orElse(defaultImages);
        return first.isEmpty() ? null : first.getFirst().url();
    }
}
