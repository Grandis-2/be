package com.grandis.nova.catalog.product;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    /** 등록의 멱등 키로 상품을 찾는다. 같은 키로 다시 온 등록 · 등록 상태 조회가 쓴다. */
    Optional<Product> findByIdempotencyKey(String idempotencyKey);

    /**
     * 상품 행을 SELECT … FOR UPDATE 로 읽는다. 관리자 수정은 전부 이것부터 잡아 한 상품에 대한 수정을 줄 세운다 — 안 잡으면 기본가 수정과
     * 추가금 수정이 겹칠 때 뒤의 것이 앞의 커밋 전 기본가로 옵션 가격을 계산해 덮고(실측: 1,400,000 이어야 할 옵션이 1,300,000),
     * 전 칼럼 UPDATE 가 그사이 다른 쓰기(공개 여부 등)를 되돌린다. 잠근 뒤 읽으므로 앞 트랜잭션이 커밋한 값을 본다(READ COMMITTED).
     * 삭제한 상품은 없는 것으로 읽는다 — 수정 · 사진 · 다시 삭제가 모두 404 다. 삭제와 겹쳐 잠금을 기다린 수정도 삭제가 커밋한 행을 다시 읽어 빈 결과다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :productId and p.deletedAt is null")
    Optional<Product> findForUpdate(@Param("productId") UUID productId);
}
