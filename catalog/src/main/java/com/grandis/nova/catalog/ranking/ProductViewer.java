package com.grandis.nova.catalog.ranking;

import java.util.Objects;
import java.util.UUID;

/** 조회수 · 인기 검색어를 세는 사람 — 로그인한 회원이면 회원 id, 아니면 브라우저가 만든 익명 id. 같은 사람이라도 둘은 다른 사람으로 센다. */
public record ProductViewer(UUID id, boolean member) {

    public ProductViewer {
        Objects.requireNonNull(id, "id");
    }

    public static ProductViewer customer(UUID customerId) {
        return new ProductViewer(customerId, true);
    }

    public static ProductViewer anonymous(UUID anonymousId) {
        return new ProductViewer(anonymousId, false);
    }

    String key() {
        return (member ? "c:" : "a:") + id;
    }
}
