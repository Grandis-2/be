package com.grandis.nova.catalog.query;

import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.catalog.listing.ProductListingQueryRepository;
import com.grandis.nova.catalog.option.OptionCombination;
import com.grandis.nova.catalog.option.ProductOptions;
import com.grandis.nova.catalog.product.Discount;
import com.grandis.nova.common.BusinessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 다른 서비스가 묻는 상품 · 옵션 읽기. catalog 소유 표 둘만 읽는다 — 회차 · 재고는 싣지 않는다(preorder · order 소유).
 *
 * registrationCompleted 는 DB 로 아는 판매 방식별 준비다({@link ProductListingQueryRepository#READY}) — 일반은 판매 준비 이벤트로 채운 칸, 사전예약은 늘
 * true(preorder 를 부르지 않는다 — 접수가 이 응답을 동기로 부르므로 순환 호출이 된다).
 * 노출 칸(공개 · 판매 상태 · 준비)과 옵션(판매 상태 · 가격)은 접수 · 장바구니 판정에 같이 쓰이므로 한 시점이어야 한다 — 따로 읽으면 사이에 커밋된
 * 판매 중지 · 공개 전환이 섞여 한순간도 없던 조합이 나온다. 그래서 둘을 한 문장으로 읽는다({@link ProductOptionsQueryRepository}) — 트랜잭션 없이 한 시점이다.
 */
@Service
public class ProductOptionsQueryService {

    private final ProductOptionsQueryRepository queries;

    public ProductOptionsQueryService(ProductOptionsQueryRepository queries) {
        this.queries = queries;
    }

    /** 없는 상품이면 PRODUCT_NOT_FOUND(404) — 삭제한 상품도 없는 상품이다. 옵션이 없는 상품은 404 가 아니라 빈 목록이다. */
    public ProductOptionsView findProductOptions(UUID productId) {
        ProductOptionsQueryRepository.ProductWithOptions found = queries.findProductWithOptions(productId)
                .filter(candidate -> !candidate.product().deleted())
                .orElseThrow(() -> new BusinessException(CatalogErrorCode.PRODUCT_NOT_FOUND));
        ProductOptionsQueryRepository.ProductRow product = found.product();
        List<ProductOptionsView.Option> optionViews = found.options().stream()
                .map(option -> new ProductOptionsView.Option(option.id(), option.sku(), option.title(), Discount.apply(option.price(), product.discountRate()),
                        option.status()))
                .toList();
        return new ProductOptionsView(product.id(), product.title(), product.saleMode(), product.status(), product.visible(), product.ready(), optionViews);
    }

    /**
     * 옵션 여러 개를 그 상품의 사실과 함께 — order(장바구니 · 바로 구매 · 사전예약 주문 사진 · 드로우 회차)가 부른다. 없는 옵션은 빠지고(삭제한 상품의 옵션도), 결과는 요청 순서(같은 id 는 한 번)다.
     * 호출자는 빠진 옵션을 판매 종료로 본다(contracts/order-internal.md).
     */
    public List<OptionLookupView> findOptions(Collection<UUID> optionIds) {
        LinkedHashSet<UUID> requested = new LinkedHashSet<>(optionIds);
        Map<UUID, ProductOptionsQueryRepository.OptionWithProduct> found = new HashMap<>();
        Map<UUID, ProductOptions> documents = new HashMap<>();   // 상품 문서(JSON)는 상품마다 한 행에만 실려 온다 — 그 행에서 한 번만 푼다
        for (ProductOptionsQueryRepository.OptionWithProduct row : queries.findOptionsWithProducts(requested)) {
            found.put(row.option().id(), row);
            if (row.document() != null && !row.product().deleted()) {   // 삭제한 상품의 옵션은 빠지므로 풀지 않는다
                documents.put(row.product().id(), ProductOptions.parse(row.document()));
            }
        }

        List<OptionLookupView> views = new ArrayList<>();
        for (UUID optionId : requested) {
            ProductOptionsQueryRepository.OptionWithProduct row = found.get(optionId);
            if (row == null || row.product().deleted()) {
                continue;
            }
            ProductOptionsQueryRepository.ProductRow product = row.product();
            ProductOptionsQueryRepository.OptionRow option = row.option();
            // 문서 칸은 NOT NULL 이라 결과에 나온 상품마다 문서가 실린 행이 하나 있다 — 없으면 문장이 틀린 것이다(빈 문서로 가리지 않는다)
            ProductOptions document = documents.get(product.id());
            if (document == null) {
                throw new IllegalStateException("옵션 조회 결과에 상품 문서가 없다: productId=" + product.id());
            }
            ProductOptions.Warranty warranty = document.warranty();
            views.add(new OptionLookupView(option.id(), product.id(), product.title(), product.modelName(), option.title(), option.sku(),
                    Discount.apply(option.price(), product.discountRate()), option.price(), option.status(), product.saleMode(), product.status(),
                    product.visible(), product.ready(), new OptionLookupView.Warranty(warranty.offered(), warranty.surcharge()),
                    document.imageUrlOf(OptionCombination.valueIdsOf(option.combinationKey()))));
        }
        return views;
    }
}
