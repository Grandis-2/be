package com.grandis.nova.catalog.promotion;

import com.grandis.nova.catalog.product.SaleMode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 홍보에 묶을 수 있는 상품(관리자 연결 화면).
 *
 * @param opensAt  회차 오픈 시각. 회차 행이 없으면 null
 * @param colors   색상 축 값(표시 문구, 넣은 순). 축이 없으면 빈 목록
 * @param storages 용량 축 값
 */
public record LinkableProduct(UUID productId, String title, SaleMode saleMode, Instant opensAt, List<String> colors, List<String> storages) {
}
