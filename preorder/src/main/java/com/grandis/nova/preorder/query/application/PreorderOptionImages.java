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
 * 사진은 표시용이라 catalog 가 답하지 않거나 거절하거나 읽을 수 없는 응답을 줘도 목록 · 상세는 그대로 내고 사진만 비운다. 일시 장애(응답 없음 ·
 * 시간 초과 · 동시 상한 · 401)면 남은 상품을 부르지 않는다 — 캐시에 없는 상품마다 시간 초과를 기다리지 않게, 접수와 나눠 쓰는 catalog 동시 상한을
 * 덜 차지하게. 그 상품에만 해당하는 실패(연동 오류 · 읽을 수 없는 응답)는 그 상품만 비우고 계속 묻는다. 상품이 없어졌거나 사진이 없는 옵션도 비어 있다.
 * 관리자 전용 목록 · 상세는 이것을 부르지 않는다(사진 칸이 null).
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
        for (UUID productId : productIds) {
            try {
                catalog.findProduct(productId).ifPresent(product -> products.put(productId, product));
            } catch (BusinessException e) {
                log.warn("예약 사진을 catalog 에서 받지 못해 비운다 — 남은 상품은 묻지 않는다 failedAt={} total={} cause={}",
                        productId, productIds.size(), e.getMessage());
                break;
            } catch (IllegalStateException e) {
                // 연동 오류(401 · 404 밖의 4xx) · 읽을 수 없는 응답 — InternalCallFailures 가 ERROR 와 스택을 남겼다. 그 상품만 비운다
                log.warn("예약 사진을 catalog 에서 읽지 못해 그 상품만 비운다(연동 오류) productId={} cause={}", productId, e.getMessage());
            } catch (RuntimeException e) {
                // 분류되지 않은 실패(빈 응답 본문 등) — 우리 쪽 결함일 수 있어 스택을 남긴다. 그 상품만 비운다
                log.error("예약 사진을 받다 예상 밖의 실패로 그 상품만 비운다 productId={}", productId, e);
            }
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
