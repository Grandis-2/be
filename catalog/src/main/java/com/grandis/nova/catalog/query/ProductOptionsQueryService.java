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

import java.util.List;

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
    public ProductOptionsView findProductOptions(Long productId) {
        ProductListingQueryRepository.Exposure exposure = crossReads.findExposure(productId)
                .orElseThrow(() -> new BusinessException(CatalogErrorCode.PRODUCT_NOT_FOUND));
        Product product = products.findById(productId)
                .orElseThrow(() -> new BusinessException(CatalogErrorCode.PRODUCT_NOT_FOUND));
        List<ProductOptionsView.Option> optionViews = options.findByProductIdOrderById(productId).stream()
                .map(ProductOptionsQueryService::toView)
                .toList();
        return new ProductOptionsView(product.getId(), product.getTitle(), product.getSaleMode(), exposure.status(),
                exposure.visible(), exposure.ready(), optionViews);
    }

    private static ProductOptionsView.Option toView(ProductOption option) {
        return new ProductOptionsView.Option(option.getId(), option.getSku(), option.getTitle(), option.getPrice(),
                option.getStatus());
    }
}
