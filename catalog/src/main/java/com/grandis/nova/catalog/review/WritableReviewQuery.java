package com.grandis.nova.catalog.review;

import com.grandis.nova.catalog.integration.Dependencies;
import com.grandis.nova.catalog.integration.InternalCalls;
import com.grandis.nova.catalog.integration.OrderClient;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.ProductRepository;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.common.OffsetPage;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 리뷰를 쓸 수 있는 내 주문상품 — 쓰기({@link ReviewService#write})가 받아 줄 것만 고른다. 배송 완료된 장바구니 · 바로 구매 주문의 주문상품은
 * order 내부 API 로 받고(catalog 는 order 표를 읽지 않는다), 이미 리뷰를 쓴 것 · 지금 사전예약 상품인 것을 catalog 표로 뺀다.
 *
 * <p>리뷰 · 주문 상태가 바뀌면 바로 보여야 해서 캐시하지 않는다 — 쓰고 돌아온 목록에 방금 쓴 주문상품이 남으면 안 된다. 쪽 나누기는
 * 고른 뒤 메모리에서 한다. order 가 최근 500 주문까지만 주므로 그보다 오래된 배송 완료 주문의 주문상품은 목록 · total 에 없다
 * (그 주문상품도 리뷰 쓰기 자체는 된다). 내부 호출은 DB 트랜잭션 밖에서 한다.
 */
@Service
public class WritableReviewQuery {

    private final OrderClient orders;
    private final ProductReviewRepository reviews;
    private final ProductRepository products;

    public WritableReviewQuery(OrderClient orders, ProductReviewRepository reviews, ProductRepository products) {
        this.orders = orders;
        this.reviews = reviews;
        this.products = products;
    }

    /** 주문 최신순, 한 주문 안은 주문상품 id 순. page 는 0 부터. */
    public OffsetPage<WritableItemView> find(UUID customerId, int page, int size) {
        List<OrderClient.DeliveredItem> delivered = InternalCalls.call(Dependencies.ORDER, orders::getDeliveredItems).items();
        if (delivered.isEmpty()) {
            return OffsetPage.of(List.of(), page, size, 0);
        }
        Set<UUID> reviewed = new HashSet<>(reviews.findReviewedOrderItemIds(customerId,
                delivered.stream().map(OrderClient.DeliveredItem::orderItemId).toList()));
        Map<UUID, Product> byId = products.findAllById(delivered.stream().map(OrderClient.DeliveredItem::productId).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        List<WritableItemView> writable = delivered.stream()
                .filter(item -> !reviewed.contains(item.orderItemId()))
                .filter(item -> isNotPreorder(byId.get(item.productId())))
                .map(WritableItemView::of)
                .toList();
        long from = (long) page * size;
        List<WritableItemView> items = from >= writable.size() ? List.of()
                : writable.subList((int) from, (int) Math.min(writable.size(), from + size));
        return OffsetPage.of(items, page, size, writable.size());
    }

    /** 상품은 물리 삭제가 없다 — order 가 준 상품이 catalog 에 없으면 계약 위반이다(쓰기와 같다). */
    private static boolean isNotPreorder(Product product) {
        if (product == null) {
            throw InternalCalls.contractViolation(Dependencies.ORDER, "catalog 에 없는 상품의 주문상품");
        }
        return product.getSaleMode() != SaleMode.PREORDER;
    }
}
