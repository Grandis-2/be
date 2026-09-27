package com.grandis.nova.catalog.api;

import java.util.List;

/** 페이지가 없는 목록 응답의 본문. 계약이 { "items": [...] } 한 겹을 요구한다. */
public record Items<T>(List<T> items) {
}
