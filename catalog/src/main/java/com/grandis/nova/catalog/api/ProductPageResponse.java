package com.grandis.nova.catalog.api;

import com.grandis.nova.common.OffsetPage;

import java.util.List;

/** 계약의 목록 봉투 — page · size · total · hasNext · items. hasNext 는 OffsetPage 가 계산하지만 record 칸이 아니라 직렬화되지 않아 여기서 편다. */
public record ProductPageResponse<T>(int page, int size, long total, boolean hasNext, List<T> items) {

    static <T> ProductPageResponse<T> from(OffsetPage<T> pageResult) {
        return new ProductPageResponse<>(pageResult.page(), pageResult.size(), pageResult.total(), pageResult.hasNext(),
                pageResult.items());
    }
}
