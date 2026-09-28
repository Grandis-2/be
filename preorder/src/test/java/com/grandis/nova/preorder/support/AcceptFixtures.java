package com.grandis.nova.preorder.support;

import com.grandis.nova.preorder.accept.AcceptResult;
import com.grandis.nova.preorder.accept.PreorderAcceptService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;

import java.time.Instant;

import static org.mockito.BDDMockito.given;

/**
 * 조회 테스트용 접수. 상품 · 회차 · 차수를 새로 만들고 catalog 대역을 맞춘 뒤 실제 접수 경로로 예약을 만든다.
 * 회원당 모델 하나이므로 접수마다 새 상품을 쓴다.
 */
public class AcceptFixtures {

    private final PreorderAcceptService acceptService;
    private final ShopFixtures fixtures;
    private final CatalogClient catalogClient;

    public AcceptFixtures(PreorderAcceptService acceptService, ShopFixtures fixtures, CatalogClient catalogClient) {
        this.acceptService = acceptService;
        this.fixtures = fixtures;
        this.catalogClient = catalogClient;
    }

    public AcceptResult accept(Long customerId) {
        return accept(customerId, fixtures.openPreorderProduct());
    }

    /** 같은 상품에 여러 예약을 만들 때 쓴다. */
    public AcceptResult accept(Long customerId, PreorderProduct product) {
        stubCatalog(product);
        return submit(customerId, product);
    }

    /** catalog 대역이 그 상품을 판매 중으로 답하게 한다. 동시 접수 전에 한 번만 부른다(스텁은 스레드 안전하지 않다). */
    public void stubCatalog(PreorderProduct product) {
        given(catalogClient.getProduct(product.productId())).willReturn(CatalogStubs.preorderProduct(
                product.productId(), CatalogStubs.activeOption(product.optionId())));
    }

    /** 스텁 없이 접수만 한다. 여러 스레드에서 불러도 된다. */
    public AcceptResult submit(Long customerId, PreorderProduct product) {
        return acceptService.acceptByCustomer(customerId, product.productId(), product.productId(),
                product.optionId(), "fixture-key-" + ShopFixtures.unique(),
                AdmissionTickets.issue(product.productId(), customerId, Instant.now()));
    }

    /** 예약의 공개 UUID. 조회 API 는 이 값으로 부른다. */
    public static String tokenOf(AcceptResult result) {
        return result.preorder().preorderToken();
    }
}
