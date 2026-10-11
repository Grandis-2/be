package com.grandis.nova.catalog.edit;

import java.util.UUID;

/** 공개 여부 전환 결과. */
public record VisibilityView(UUID productId, boolean visible) {
}
