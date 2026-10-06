package com.grandis.nova.common;

import java.util.List;
import java.util.function.Function;

/**
 * 오프셋 페이징 결과. 기준은 "누가 쓰나" 가 아니라 **목록이 빨리 느는가 · 전체 건수가 필요한가** 다.
 *
 * 접수처럼 계속 늘어나는 목록은 {@link CursorPage} 를 쓴다 — 오프셋은 페이지를 넘기는 사이에 항목이 밀려 겹치거나 빠진다.
 * 관리자 화면처럼 전체 건수와 페이지 번호가 필요하고 COUNT 비용을 감당할 수 있는 목록, 그리고 상품 목록처럼 천천히 바뀌면서
 * 계약이 total · hasNext 를 요구하는 목록은 오프셋을 쓴다.
 *
 * page 는 0 부터 센다.
 */
public record OffsetPage<T>(List<T> items, int page, int size, long total) {

    public OffsetPage {
        if (page < 0 || size < 1 || total < 0) {
            throw new IllegalArgumentException("page=%d size=%d total=%d".formatted(page, size, total));
        }
        items = List.copyOf(items);
    }

    public static <T> OffsetPage<T> of(List<T> items, int page, int size, long total) {
        return new OffsetPage<>(items, page, size, total);
    }

    /** 올림 나눗셈을 몫과 나머지로 한다. total + size - 1 은 total 이 클 때 넘친다. */
    public long totalPages() {
        return total / size + (total % size == 0 ? 0 : 1);
    }

    public boolean hasNext() {
        return ((long) page + 1) * size < total;
    }

    public <R> OffsetPage<R> map(Function<T, R> mapper) {
        return new OffsetPage<>(items.stream().map(mapper).toList(), page, size, total);
    }
}
