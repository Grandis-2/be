package com.grandis.nova.order.client.catalog;

import java.util.List;

/** catalog 옵션 일괄 조회 응답의 data — 계약이 { "items": [...] } 한 겹을 요구한다. */
public record CatalogOptions(List<CatalogOption> items) {

    public CatalogOptions {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
