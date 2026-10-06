package com.grandis.nova.catalog.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductReviewRepository extends JpaRepository<ProductReview, Long> {

    /** 작성자 본인의 리뷰만. 남의 리뷰는 없는 것과 같다(404). */
    Optional<ProductReview> findByIdAndCustomerId(Long id, Long customerId);
}
