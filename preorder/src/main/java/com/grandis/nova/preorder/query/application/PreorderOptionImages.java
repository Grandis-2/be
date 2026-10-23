package com.grandis.nova.preorder.query.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.integration.catalog.CatalogReader;
import com.grandis.nova.preorder.integration.catalog.ProductCatalog;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 예약 목록 · 상세의 옵션 사진. 예약은 접수 때 사진을 복사해 두지 않으므로, 접수가 쓰는 catalog 상품 사본({@link CatalogReader}, 상품 단위 캐시)에서
 * 그 옵션의 사진(그 옵션이 고른 색상의 첫 장 — 주문 사진과 같은 값)을 꺼낸다. 캐시에 있으면 catalog 를 부르지 않는다.
 * 사진은 표시용이라 catalog 가 답하지 않거나 거절해도 목록 · 상세는 그대로 내고 사진만 비운다. 상품이 없어졌거나 사진이 없는 옵션도 비어 있다.
 */
@Component
public class PreorderOptionImages {

    private static final Logger log = LoggerFactory.getLogger(PreorderOptionImages.class);

    private final CatalogReader catalog;

    public PreorderOptionImages(CatalogReader catalog) {
        this.catalog = catalog;
    }

    /** 예약마다의 사진 — 키는 예약의 옵션 id. 사진을 모르는 옵션은 빠진다. 상품마다 사본을 한 번만 찾는다. */
    public Map<UUID, String> of(Collection<PreorderSnapshot> preorders) {
        Set<UUID> productIds = new LinkedHashSet<>();
        preorders.forEach(preorder -> productIds.add(preorder.productId()));
        Map<UUID, ProductCatalog> products = new HashMap<>();
        int failed = 0;
        for (UUID productId : productIds) {
            try {
                catalog.findProduct(productId).ifPresent(product -> products.put(productId, product));
            } catch (BusinessException e) {
                failed++;
            }
        }
        if (failed > 0) {
            log.warn("예약 사진을 catalog 에서 받지 못해 비운다 products={}/{}", failed, productIds.size());
        }
        Map<UUID, String> images = new HashMap<>();
        for (PreorderSnapshot preorder : preorders) {
            Optional.ofNullable(products.get(preorder.productId()))
                    .flatMap(product -> product.imageUrlOf(preorder.optionId()))
                    .ifPresent(url -> images.put(preorder.optionId(), url));
        }
        return images;
    }
}
