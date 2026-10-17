package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.BusinessException;

/** 목록 크기 규칙(계약: page 0 이상, size 1~100, 앞에서 10,000개까지 — (page + 1) × size ≤ 10,000). */
final class PageSizes {

    static final int DEFAULT = 20;
    static final int MIN = 1;
    static final int MAX = 100;
    /** 목록을 볼 수 있는 깊이. offset 페이징이라 깊은 쪽은 건너뛴 행을 다 읽고 버린다. */
    static final int MAX_WINDOW = 10_000;

    private PageSizes() {
    }

    /** @throws BusinessException VALIDATION_FAILED — 범위를 벗어난 size */
    static int require(int size) {
        if (size < MIN || size > MAX) {
            throw ValidationFailures.of("size", "%d~%d 이어야 합니다.".formatted(MIN, MAX));
        }
        return size;
    }

    /**
     * 크기와 함께 본다 — 그 쪽의 끝((page + 1) × size)이 10,000 을 넘으면 거절한다. 건너뛸 행 수가 int 를 넘으면
     * Spring Data 페이징(관리자 홍보 목록)이 500 을 냈다(실측) — 이 상한이 그 전에 막는다. size 를 먼저 검사한 뒤 부른다.
     *
     * @throws BusinessException VALIDATION_FAILED(page) — 음수 · 너무 깊음
     */
    static int requirePage(int page, int size) {
        if (page < 0) {
            throw ValidationFailures.of("page", "0 이상이어야 합니다.");
        }
        if (((long) page + 1) * size > MAX_WINDOW) {
            throw ValidationFailures.of("page", "앞에서 10,000개까지만 볼 수 있습니다.");
        }
        return page;
    }
}
