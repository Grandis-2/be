package com.grandis.nova.order.stock.domain.repository;

import com.grandis.nova.order.stock.domain.enums.SaleMode;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * catalog 소유 표(products · product_options)를 읽는 유일한 포트. 쓰지 않는다.
 * 이 포트의 SELECT 는 잠그지 않는다 — 옵션은 지워지지 않고 판매 방식은 등록 뒤 바뀌지 않는다는 catalog 의 규칙에 기댄다.
 * 다만 재고 행 INSERT 의 FK 검사는 product_options 부모 행에 S 잠금을 잡는다. 그 행을 고치는 catalog 트랜잭션이
 * 커밋하지 않았으면 INSERT 가 그만큼 기다린다.
 */
public interface CatalogOptions {

    Optional<SaleMode> findSaleMode(UUID productId);

    /** 그 상품의 옵션 전부(판매 중지 포함), id 오름차순. */
    List<UUID> findOptionIds(UUID productId);

    /** optionIds 중 그 상품의 옵션인 것. */
    Set<UUID> findOwnedOptionIds(UUID productId, Collection<UUID> optionIds);
}
