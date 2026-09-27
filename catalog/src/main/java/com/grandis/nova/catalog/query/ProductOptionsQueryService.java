package com.grandis.nova.catalog.query;

import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.ProductOption;
import com.grandis.nova.catalog.product.ProductOptionRepository;
import com.grandis.nova.catalog.product.ProductRepository;
import com.grandis.nova.catalog.product.ProductWithRegistration;
import com.grandis.nova.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 다른 서비스가 묻는 상품 · 옵션 읽기. catalog 소유 표 셋만 읽는다 — 회차 · 재고는 싣지 않는다(preorder · order 소유).
 *
 * 상품과 등록 완료는 한 SELECT 로 읽는다({@link ProductRepository#findWithRegistration}). READ COMMITTED 는 문장마다
 * 스냅샷을 새로 잡으므로 둘을 따로 읽으면 완료 커밋이 사이에 끼어 (visible=true, completed=false) 같은 조합이 나온다 —
 * 계약이 "없다" 고 한 조합이고 preorder 가 캐시하면 1분은 남는다. 옵션 목록은 이 불변식과 무관하니 따로 읽는다.
 */
@Service
public class ProductOptionsQueryService {

    private final ProductRepository products;
    private final ProductOptionRepository options;

    public ProductOptionsQueryService(ProductRepository products, ProductOptionRepository options) {
        this.products = products;
        this.options = options;
    }

    /** 없는 상품이면 PRODUCT_NOT_FOUND(404). 옵션이 없는 상품은 404 가 아니라 빈 목록이다. */
    @Transactional(readOnly = true)
    public ProductOptionsView findProductOptions(Long productId) {
        ProductWithRegistration found = products.findWithRegistration(productId)
                .orElseThrow(() -> new BusinessException(CatalogErrorCode.PRODUCT_NOT_FOUND));
        Product product = found.product();
        List<ProductOptionsView.Option> optionViews = options.findByProductIdOrderById(productId).stream()
                .map(ProductOptionsQueryService::toView)
                .toList();
        return new ProductOptionsView(product.getId(), product.getTitle(), product.getSaleMode(), product.getStatus(),
                product.isVisible(), found.isRegistrationCompleted(), optionViews);
    }

    private static ProductOptionsView.Option toView(ProductOption option) {
        return new ProductOptionsView.Option(option.getId(), option.getSku(), option.getTitle(), option.getPrice(),
                option.getStatus());
    }
}
