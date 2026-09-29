package com.grandis.nova.catalog.product;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * 상품 행을 SELECT … FOR UPDATE 로 읽는다. 관리자 수정은 전부 이것부터 잡아 한 상품에 대한 수정을 줄 세운다 — 안 잡으면 기본가 수정과
     * 추가금 수정이 겹칠 때 뒤의 것이 앞의 커밋 전 기본가로 옵션 가격을 계산해 덮고(실측: 1,400,000 이어야 할 옵션이 1,300,000),
     * 전 칼럼 UPDATE 가 그사이 다른 쓰기(공개 여부 등)를 되돌린다. 잠근 뒤 읽으므로 앞 트랜잭션이 커밋한 값을 본다(READ COMMITTED).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :productId")
    Optional<Product> findForUpdate(@Param("productId") Long productId);
}
