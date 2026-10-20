package com.grandis.nova.member.customer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByKakaoId(String kakaoId);

    /**
     * 회원 행 하나를 잠그지 않고 읽는다. 직접 선언한 쿼리라 트랜잭션 밖에서 부르면 문장 하나다 — 상속한 findById 는 저장소가 읽기 전용 트랜잭션을
     * 열어 준비 · 정리 문장 5개가 더 붙는다(실측 MySQL 8.4 일반 로그: findById 6문장, 선언한 쿼리 1문장).
     */
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> lookup(@Param("id") UUID id);

    /**
     * 회원 행을 SELECT … FOR UPDATE 로 읽는다. 내 정보 · 기본 배송지 쓰기는 이것부터 잡는다 — 바뀐 칸만 쓰는 UPDATE(@DynamicUpdate)는
     * 잠그지 않고 읽으면 같은 자원의 PUT 두 개가 칸마다 섞인다(이름은 한쪽, 이메일은 다른 쪽 — 재현: ProfileIntegrationTest).
     * 잠근 뒤 읽으므로 앞 트랜잭션이 커밋한 값을 보고, 늦은 PUT 의 본문이 그대로 남는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> findForUpdate(@Param("id") UUID id);

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
