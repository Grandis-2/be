package com.grandis.nova.catalog.query;

import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.catalog.listing.ProductListingQueryRepository;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.ProductOption;
import com.grandis.nova.catalog.product.ProductOptionRepository;
import com.grandis.nova.catalog.product.ProductRepository;
import com.grandis.nova.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.grandis.nova.catalog.option.ProductOptions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.UUID;

/**
 * 다른 서비스가 묻는 상품 · 옵션 읽기. catalog 소유 표 셋만 읽는다 — 회차 · 재고는 싣지 않는다(preorder · order 소유).
 *
 * registrationCompleted 는 판매 방식별 준비다 — 회차 · 재고 표를 읽는 유일한 자리({@link ProductListingQueryRepository})에 묻는다.
 * 사전예약 접수는 visible · status · registrationCompleted 를 함께 보고 판정하므로 셋은 한 문장으로 읽는다({@link ProductListingQueryRepository#findExposure}).
 * READ COMMITTED 에서 따로 읽으면 사이에 공개 전환과 회차 생성이 커밋돼 한순간도 없던 조합(공개 · 준비)이 나온다.
 * 옵션(판매 상태 · 가격)도 접수 판정에 쓰이므로(옵션이 ACTIVE 인가) 노출 칸과 한 스냅샷으로 읽는다 — REPEATABLE READ 트랜잭션.
 */
@Service
public class ProductOptionsQueryService {

    private final ProductRepository products;
    private final ProductOptionRepository options;
    private final ProductListingQueryRepository crossReads;

    public ProductOptionsQueryService(ProductRepository products, ProductOptionRepository options,
                                      ProductListingQueryRepository crossReads) {
        this.products = products;
        this.options = options;
        this.crossReads = crossReads;
    }

    /** 없는 상품이면 PRODUCT_NOT_FOUND(404). 옵션이 없는 상품은 404 가 아니라 빈 목록이다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductOptionsView findProductOptions(UUID productId) {
        ProductListingQueryRepository.Exposure exposure = crossReads.findExposure(productId)
                .orElseThrow(() -> new BusinessException(CatalogErrorCode.PRODUCT_NOT_FOUND));
        Product product = products.findById(productId)
                .orElseThrow(() -> new BusinessException(CatalogErrorCode.PRODUCT_NOT_FOUND));
        List<ProductOptionsView.Option> optionViews = options.findByProductIdOrderByCreatedAtAscIdAsc(productId).stream()
                .map(ProductOptionsQueryService::toView)
                .toList();
        return new ProductOptionsView(product.getId(), product.getTitle(), product.getSaleMode(), exposure.status(),
                exposure.visible(), exposure.ready(), optionViews);
    }

    /**
     * 옵션 여러 개를 그 상품의 사실과 함께 — 장바구니(order)가 부른다. 없는 옵션은 빠지고, 결과는 요청 순서(같은 id 는 한 번)다.
     * 노출 칸 · 옵션 · 상품을 한 스냅샷으로 읽는다(REPEATABLE READ) — 따로 읽으면 사이에 커밋된 판매 중지 · 공개 전환이 섞여 한순간도 없던 조합이 나온다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<OptionLookupView> findOptions(Collection<UUID> optionIds) {
        Map<UUID, ProductOption> found = new HashMap<>();
        options.findAllById(new LinkedHashSet<>(optionIds)).forEach(option -> found.put(option.getId(), option));
        Set<UUID> productIds = new HashSet<>();
        found.values().forEach(option -> productIds.add(option.getProductId()));
        Map<UUID, Product> productsById = new HashMap<>();
        products.findAllById(productIds).forEach(product -> productsById.put(product.getId(), product));
        Map<UUID, ProductListingQueryRepository.Exposure> exposures = crossReads.findExposures(productIds);
        Map<UUID, ProductOptions.Warranty> warranties = new HashMap<>();   // 상품 문서(JSON)는 상품마다 한 번만 푼다

        List<OptionLookupView> views = new ArrayList<>();
        for (UUID optionId : new LinkedHashSet<>(optionIds)) {
            ProductOption option = found.get(optionId);
            if (option == null) {
                continue;
            }
            Product product = productsById.get(option.getProductId());
            ProductListingQueryRepository.Exposure exposure = exposures.get(option.getProductId());
            if (product == null || exposure == null) {
                throw new IllegalStateException("옵션의 상품이 같은 스냅샷에 없다(FK 위반?): option=" + optionId);
            }
            ProductOptions.Warranty warranty = warranties.computeIfAbsent(product.getId(), id -> product.getOptions().warranty());
            views.add(new OptionLookupView(option.getId(), product.getId(), product.getTitle(), option.getTitle(), option.getSku(),
                    option.getPrice(), option.getStatus(), product.getSaleMode(), exposure.status(), exposure.visible(), exposure.ready(),
                    new OptionLookupView.Warranty(warranty.offered(), warranty.surcharge()), product.getThumbnailUrl()));
        }
        return views;
    }

    private static ProductOptionsView.Option toView(ProductOption option) {
        return new ProductOptionsView.Option(option.getId(), option.getSku(), option.getTitle(), option.getPrice(),
                option.getStatus());
    }
}
