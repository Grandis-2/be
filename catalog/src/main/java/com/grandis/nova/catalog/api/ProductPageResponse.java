package com.grandis.nova.catalog.api;

import com.grandis.nova.common.OffsetPage;

import java.util.List;

/**
 * 계약의 목록 봉투 — page · size · total · hasNext · items. hasNext 는 OffsetPage 가 계산하지만 record 칸이 아니라 직렬화되지 않아 여기서 편다.
 * hasNext 는 다음 쪽이 있고 <b>볼 수도 있을 때</b>만 true 다 — 다음 쪽이 앞 10,000개 밖에서 시작하면({@link PageSizes}) 요청이 400 이라 false.
 */
public record ProductPageResponse<T>(int page, int size, long total, boolean hasNext, List<T> items) {

    static <T> ProductPageResponse<T> from(OffsetPage<T> pageResult) {
        boolean nextIsReachable = ((long) pageResult.page() + 1) * pageResult.size() < PageSizes.MAX_WINDOW;
        return new ProductPageResponse<>(pageResult.page(), pageResult.size(), pageResult.total(),
                pageResult.hasNext() && nextIsReachable, pageResult.items());
    }
}
