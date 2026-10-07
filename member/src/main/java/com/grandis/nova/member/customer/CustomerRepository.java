package com.grandis.nova.member.customer;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByKakaoId(String kakaoId);

    /**
     * 표시명만 바꾼다. 로그인은 트랜잭션 밖이라 엔티티를 고쳐 저장하면 그사이 바뀐 내 정보 · 배송지를 덮을 수 있어 한 문장으로 쓴다.
     * 바뀌었는지는 호출자가 Java 로 가린다 — SQL 의 {@code <>} 는 칼럼 콜레이션(utf8mb4_0900_ai_ci)이라 대소문자 · 악센트만 다른 값(kim → Kim)을
     * 같다고 보고 0 행이 된다(실측).
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Customer c set c.displayName = :displayName, c.updatedAt = :now where c.id = :id")
    int refreshDisplayName(@Param("id") UUID id, @Param("displayName") String displayName, @Param("now") Instant now);
}
