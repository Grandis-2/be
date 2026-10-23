package com.grandis.nova.catalog.review;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductReviewRepository extends JpaRepository<ProductReview, UUID> {

    /** 그 주문상품들 중 이 회원이 리뷰를 쓴 것. */
    @Query("select r.orderItemId from ProductReview r where r.customerId = :customerId and r.orderItemId in :orderItemIds")
    List<UUID> findReviewedOrderItemIds(@Param("customerId") UUID customerId, @Param("orderItemIds") Collection<UUID> orderItemIds);

    /** 작성자 본인의 리뷰만. 남의 리뷰는 없는 것과 같다(404). */
    Optional<ProductReview> findByIdAndCustomerId(UUID id, UUID customerId);

    /**
     * 작성자 본인의 리뷰를 SELECT … FOR UPDATE 로 읽는다. 고치기는 이것부터 잡는다 — 안 잡으면 별점만 고치는 요청이 그사이 커밋된 본문 수정을
     * 전 칼럼 UPDATE 로 되돌린다(재현: ReviewApiTest). 잠근 뒤 읽으므로 앞 트랜잭션이 커밋한 값을 본다(READ COMMITTED).
     * 작성자 조건은 남의 행 잠금까지 막지는 못한다 — PK 로 찾은 행을 잠근 뒤 조건을 보므로 남의 리뷰 id 면 빈 결과여도 그 행을 잠깐 잠근다
     * (MySQL 8.4.11 리뷰 때 실측). 빈 결과는 곧바로 404 · 롤백이라 잠금은 짧다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProductReview r where r.id = :id and r.customerId = :customerId")
    Optional<ProductReview> findMineForUpdate(@Param("id") UUID id, @Param("customerId") UUID customerId);
}
