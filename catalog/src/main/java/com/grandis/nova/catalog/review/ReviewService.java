package com.grandis.nova.catalog.review;

import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.catalog.integration.Dependencies;
import com.grandis.nova.catalog.integration.InternalCalls;
import com.grandis.nova.catalog.integration.MemberClient;
import com.grandis.nova.catalog.integration.OrderClient;
import com.grandis.nova.catalog.listing.ProductListingQueryRepository;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.ProductRepository;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.web.ConstraintViolations;
import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.OffsetPage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 리뷰 — 일반 판매 상품의 배송 완료된 주문상품 1건당 1개, 작성자만 수정 · 삭제, 조회는 비로그인도(2026-10-06 결정).
 *
 * <p><b>쓸 자격은 order 가 안다.</b> catalog 는 order 표를 읽지 않고 order 내부 API 로 묻는다 — 주문상품이 없거나 토큰 주인의 것이
 * 아니면(order 의 ORDER_ITEM_NOT_FOUND 404) 404, 배송 완료(DELIVERED)가 아니거나 사전예약 주문이면 409 REVIEW_NOT_ALLOWED.
 * 상품이 사전예약이어도 409 다(catalog 표로 판정). 응답이 물은 주문상품이 아니거나 필수 칸이 비면 연동 오류(500)다 — 리뷰의 상품은
 * order 응답만 정하므로(서비스를 넘는 FK 가 없다) 대조한다. 작성자 표시명은 member 내부 API 로 받아 가린 값(첫 글자 + "**")만 저장한다.
 *
 * <p>내부 호출은 DB 트랜잭션 밖에서 한다 — 응답을 기다리는 동안 연결을 붙잡지 않는다. 저장은 한 문장이고, 같은 주문상품의
 * 두 번째 리뷰는 UNIQUE(uq_review_order_item)가 막아 409 REVIEW_ALREADY_WRITTEN 이다(동시에 둘이 와도 하나만 들어간다).
 */
@Service
public class ReviewService {

    /** order 의 "주문상품 없음" 오류 코드(contracts/order-internal.md). 경로 없음의 404 는 공통 NOT_FOUND 라 이것으로 가린다. */
    static final String ORDER_ITEM_NOT_FOUND = "ORDER_ITEM_NOT_FOUND";
    static final String DELIVERED = "DELIVERED";
    static final String PREORDER_SOURCE = "PREORDER";
    private static final String MASK = "**";

    private final ProductReviewRepository reviews;
    private final ReviewQueryRepository queries;
    private final ProductRepository products;
    private final ProductListingQueryRepository crossReads;
    private final OrderClient orders;
    private final MemberClient members;

    public ReviewService(ProductReviewRepository reviews, ReviewQueryRepository queries, ProductRepository products,
                         ProductListingQueryRepository crossReads, OrderClient orders, MemberClient members) {
        this.reviews = reviews;
        this.queries = queries;
        this.products = products;
        this.crossReads = crossReads;
        this.orders = orders;
        this.members = members;
    }

    /** @throws BusinessException NOT_FOUND(내 주문상품이 아님) · REVIEW_NOT_ALLOWED · REVIEW_ALREADY_WRITTEN · VALIDATION_FAILED */
    public ReviewView write(Long customerId, ReviewCreateRequest request) {
        String body = requireBody(request.body());
        OrderClient.OrderItem item = requireContract(request.orderItemId(), InternalCalls.call(Dependencies.ORDER,
                () -> orders.getOrderItem(request.orderItemId()), ORDER_ITEM_NOT_FOUND,
                () -> new BusinessException(CommonErrorCode.NOT_FOUND, "주문상품을 찾을 수 없습니다.")));
        if (!DELIVERED.equals(item.orderStatus())) {
            throw new BusinessException(CatalogErrorCode.REVIEW_NOT_ALLOWED, "배송이 완료된 주문상품에만 리뷰를 쓸 수 있습니다.");
        }
        // 상품은 물리 삭제가 없다 — order 가 준 상품이 catalog 에 없으면 계약 위반이다
        Product product = products.findById(item.productId())
                .orElseThrow(() -> InternalCalls.contractViolation(Dependencies.ORDER, "catalog 에 없는 상품의 주문상품"));
        if (PREORDER_SOURCE.equals(item.orderSource()) || product.getSaleMode() == SaleMode.PREORDER) {
            throw new BusinessException(CatalogErrorCode.REVIEW_NOT_ALLOWED, "사전예약 상품은 리뷰를 받지 않습니다.");
        }
        // member 는 토큰의 회원이 없으면 401 로 답한다. 404 는 경로 없음(배포 순서 · 주소 오류)이라 연동 오류다 — 401 로 바꾸면
        // 프론트가 토큰 문제로 보고 재발급 · 로그아웃한다
        MemberClient.Customer author = InternalCalls.call(Dependencies.MEMBER, members::getMe);
        if (!customerId.equals(author.customerId())) {
            throw InternalCalls.contractViolation(Dependencies.MEMBER, "토큰 주인이 아닌 회원의 응답");
        }
        ProductReview review = ProductReview.write(product.getId(), customerId, item.orderItemId(), request.rating(), body,
                item.optionTitle(), mask(author.displayName()));
        try {
            reviews.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.mentionsKey(e, "uq_review_order_item")) {
                throw new BusinessException(CatalogErrorCode.REVIEW_ALREADY_WRITTEN);
            }
            throw e;
        }
        return mine(customerId, review.getOrderItemId());
    }

    /** 작성자 본인만. 남의 리뷰 · 없는 리뷰는 404. */
    @Transactional
    public ReviewView revise(Long customerId, Long reviewId, ReviewUpdateRequest request) {
        if (request.isEmpty()) {
            throw ValidationFailures.of("body", "바꿀 칸이 하나도 없습니다.");
        }
        ProductReview review = requireMine(customerId, reviewId);
        review.revise(request.rating(), request.body() == null ? null : requireBody(request.body()));
        reviews.flush();
        return mine(customerId, review.getOrderItemId());
    }

    @Transactional
    public void delete(Long customerId, Long reviewId) {
        reviews.delete(requireMine(customerId, reviewId));
    }

    /** 상품 상세의 후기 탭. 상품을 보여 주지 않는 경우(없음 · 비공개 · 준비 전)는 상품 상세처럼 404. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OffsetPage<ReviewView> productReviews(Long productId, int page, int size) {
        Product product = products.findById(productId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (!(product.isVisible() && crossReads.isReady(productId))) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return new OffsetPage<>(queries.findByProduct(productId, page, size), page, size, queries.countByProduct(productId));
    }

    /** 모아보기. 비공개 상품의 리뷰는 빠진다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OffsetPage<ReviewView> visibleReviews(Long categoryId, int page, int size) {
        return new OffsetPage<>(queries.findVisible(categoryId, page, size), page, size, queries.countVisible(categoryId));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OffsetPage<ReviewView> myReviews(Long customerId, Long orderItemId, int page, int size) {
        return new OffsetPage<>(queries.findMine(customerId, orderItemId, page, size), page, size,
                queries.countMine(customerId, orderItemId));
    }

    private ProductReview requireMine(Long customerId, Long reviewId) {
        return reviews.findByIdAndCustomerId(reviewId, customerId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND, "리뷰를 찾을 수 없습니다."));
    }

    /** 방금 쓰거나 고친 내 리뷰. 그 사이 지워졌으면(같은 회원의 동시 삭제) 404. */
    private ReviewView mine(Long customerId, Long orderItemId) {
        return queries.findMine(customerId, orderItemId, 0, 1).stream().findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND, "리뷰를 찾을 수 없습니다."));
    }

    /** order 응답이 물은 주문상품이고 필수 칸이 있는가. */
    private static OrderClient.OrderItem requireContract(Long requested, OrderClient.OrderItem item) {
        if (!requested.equals(item.orderItemId())) {
            throw InternalCalls.contractViolation(Dependencies.ORDER, "물은 주문상품과 다른 응답");
        }
        if (item.productId() == null || item.optionTitle() == null || item.orderStatus() == null || item.orderSource() == null) {
            throw InternalCalls.contractViolation(Dependencies.ORDER, "필수 칸이 빈 응답");
        }
        return item;
    }

    /**
     * 앞뒤 공백을 뺀 본문 — 보이는 글자가 없거나 2,000자를 넘으면 그 칸의 400(칼럼 varchar(2000), CHECK 길이 > 0).
     * strip() 이 못 걷는 글자(줄바꿈 없는 공백 U+00A0 · 폭 없는 공백 U+200B · BOM U+FEFF · 한글 채움 문자 U+3164 · 점자 빈칸 U+2800 ·
     * 제어 문자 · 홀로 쓴 결합 문자 …)만 있어도 빈 본문이다(실측: 걷지 않으면 통과해 DB 에 들어갔다). 길이는 UTF-16 단위로 잰다 — 이모지는 2로 세어 칼럼보다 엄격하다.
     */
    private static String requireBody(String body) {
        String stripped = body == null ? "" : body.strip();
        if (!hasVisibleCharacter(stripped)) {
            throw ValidationFailures.of("body", "리뷰 내용이 비었습니다.");
        }
        if (stripped.length() > ProductReview.MAX_BODY_LENGTH) {
            throw ValidationFailures.of("body", "리뷰는 %d자 이하입니다.".formatted(ProductReview.MAX_BODY_LENGTH));
        }
        return stripped;
    }

    /** 보이는 글자가 하나라도 있는가. 결합 문자 · 이모지 수식은 앞 글자에 붙어야 보이므로 홀로는 보이지 않는 것으로 센다. */
    private static boolean hasVisibleCharacter(String text) {
        return text.codePoints().anyMatch(cp -> !(INVISIBLE_TYPES.contains(Character.getType(cp)) || INVISIBLE_LETTERS.contains(cp)));
    }

    /** 제어 · 서식 · 사용 영역 · 미할당 · 서로게이트, 결합 표시(Mn · Me), 공백 · 줄 · 문단 구분자. */
    private static final java.util.Set<Integer> INVISIBLE_TYPES = java.util.Set.of(
            (int) Character.CONTROL, (int) Character.FORMAT, (int) Character.PRIVATE_USE, (int) Character.UNASSIGNED,
            (int) Character.SURROGATE, (int) Character.NON_SPACING_MARK, (int) Character.ENCLOSING_MARK,
            (int) Character.SPACE_SEPARATOR, (int) Character.LINE_SEPARATOR, (int) Character.PARAGRAPH_SEPARATOR);

    /** 글자(Lo) 범주지만 보이지 않는 것 — 한글 채움 문자(초성 · 중성 채움, 한글 채움, 반각 한글 채움), 점자 빈칸. */
    private static final java.util.Set<Integer> INVISIBLE_LETTERS = java.util.Set.of(0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800);

    /** 첫 글자(코드포인트) + "**". 표시명이 비었으면 "***". */
    static String mask(String displayName) {
        String name = displayName == null ? "" : displayName.strip();
        if (name.isEmpty()) {
            return "*" + MASK;
        }
        return new String(Character.toChars(name.codePointAt(0))) + MASK;
    }
}
