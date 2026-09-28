package com.grandis.nova.catalog.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * 상품과 등록 기록의 상태 칸 전부를 한 문장으로 읽는다. (visible, registrationCompleted) 를 같은 스냅샷에서 가져오기 위해서다 —
     * 따로 읽으면 완료 커밋이 사이에 끼어 계약에 없는 조합이 나온다. 둘을 갈라 읽는 쪽으로 바꾸지 않는다.
     */
    @Query("""
            select new com.grandis.nova.catalog.product.ProductWithRegistration(p, r.idempotencyKey, r.campaignSetAt,
                       r.batchesSetAt, r.stockSetAt, r.completedAt, r.blockedReason, r.lastError)
              from Product p
              left join ProductRegistration r on r.productId = p.id
             where p.id = :productId
            """)
    Optional<ProductWithRegistration> findWithRegistration(@Param("productId") Long productId);
}
