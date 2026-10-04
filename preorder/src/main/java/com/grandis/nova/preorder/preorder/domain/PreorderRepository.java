package com.grandis.nova.preorder.preorder.domain;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 상태를 바꾸는 쿼리는 모두 "현재 상태를 조건으로 한 UPDATE" 이고 영향 행 수를 돌려준다.
 * 다음 상태는 상태 머신({@link PreorderStatus#next})이 정하고, 이력은 {@link PreorderLedger} 가 함께 남긴다 —
 * 서비스가 이 메서드를 직접 부르지 않는다.
 *
 * 벌크 UPDATE 는 영속성 컨텍스트를 거치지 않으므로 앞뒤로 flush · clear 한다. 그러지 않으면
 * 같은 트랜잭션에서 이미 읽어 둔 엔티티가 옛 상태를 들고 있다.
 */
public interface PreorderRepository extends JpaRepository<Preorder, Long>, JpaSpecificationExecutor<Preorder> {

    /** 공개 UUID 로 찾는다. 조회 API 는 이 값만 받는다 — 내부 id 는 밖에 알리지 않는다. */
    Optional<Preorder> findByPreorderToken(String preorderToken);

    /**
     * API 가 받은 공개 UUID 의 예약. 없으면 404 다.
     * 이벤트처럼 "없으면 메시지가 잘못된 것" 인 곳은 이것 대신 {@link #findByPreorderToken} 을 쓴다.
     */
    default Preorder getByToken(String preorderToken) {
        return findByPreorderToken(preorderToken)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PREORDER_NOT_FOUND));
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Preorder p
               set p.status = :to, p.eventSequence = p.eventSequence + 1, p.updatedAt = :now
             where p.id = :id and p.status = :from
            """)
    int changeStatus(@Param("id") Long id, @Param("from") PreorderStatus from, @Param("to") PreorderStatus to,
                     @Param("now") Instant now);

    /**
     * 주문 쪽 취소 거절 → PAYABLE 로 되돌림. 결제 가능한 적이 있는(payable_from 이 있는) 예약만 되돌린다 —
     * PENDING_SYNC 에서 시작한 취소를 되돌리면 결제 기한 기준 시각이 없는 PAYABLE 이 생긴다(ck_preorder_payable_from).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Preorder p
               set p.status = :payable, p.eventSequence = p.eventSequence + 1, p.updatedAt = :now
             where p.id = :id and p.status = :from and p.payableFrom is not null
            """)
    int revertToPayable(@Param("id") Long id, @Param("now") Instant now,
                        @Param("from") PreorderStatus from, @Param("payable") PreorderStatus payable);

    /** 등록 확인 반영. payable_from 은 처음 한 번만 찍는다(여기서 24시간이 결제 기한). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Preorder p
               set p.status = :payable,
                   p.payableFrom = coalesce(p.payableFrom, :now),
                   p.externalReference = :externalReference,
                   p.eventSequence = p.eventSequence + 1,
                   p.updatedAt = :now
             where p.id = :id and p.status = :from
            """)
    int markPayable(@Param("id") Long id, @Param("externalReference") String externalReference,
                    @Param("now") Instant now,
                    @Param("from") PreorderStatus from, @Param("payable") PreorderStatus payable);

    /** 같은 접수 키의 기존 예약(재전송 판정). */
    Optional<Preorder> findByCustomerIdAndIdempotencyKey(Long customerId, String idempotencyKey);

    /** 회원의 그 모델 마지막 접수 시각(상태 무관). uq_preorder_active 의 앞부분(customer_id, product_id)으로 찾는다. */
    @Query("select max(p.createdAt) from Preorder p where p.customerId = :customerId and p.productId = :productId")
    Optional<Instant> findLastAcceptedAt(@Param("customerId") Long customerId, @Param("productId") Long productId);

    /** 같은 모델의 진행 중 예약(취소 완료 제외). 활성 예약 UNIQUE 충돌 때 기존 예약을 알려 주려고 쓴다. */
    Optional<Preorder> findFirstByCustomerIdAndProductIdAndStatusNot(Long customerId, Long productId,
                                                                    PreorderStatus status);

    /**
     * 상태를 읽으며 예약 행을 잠근다. 사건을 판정하고 반영할 때까지 다른 트랜잭션이 상태를 바꾸지 못한다.
     * 잠금 순서는 예약 행 → 그 예약의 작업 행이다. 작업 행을 먼저 잠근 뒤 이걸 부르지 않는다(교착).
     */
    @Query(value = "SELECT status FROM preorders WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<PreorderStatus> findStatusForUpdate(@Param("id") Long id);

    /** 방금 올린 이력 번호. 같은 트랜잭션의 UPDATE 가 행을 잠그고 있으므로 다른 트랜잭션이 끼어들 수 없다. */
    @Query("select p.eventSequence from Preorder p where p.id = :id")
    long findEventSequence(@Param("id") Long id);

    /** 예약 행을 잠그고 읽는다. 트랜잭션에서 처음 읽을 때 불러야 잠근 뒤의 최신 값을 받는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Preorder p where p.preorderToken = :token")
    Optional<Preorder> findForUpdateByPreorderToken(@Param("token") String token);

    /** 상품의 예약을 순번 순으로 읽는다. uq_preorder_position(product_id, queue_position) 을 탄다. */
    List<Preorder> findByProductIdAndStatusInOrderByQueuePosition(Long productId, Collection<PreorderStatus> statuses,
                                                                   Limit limit);

    /** 상태별 예약 수. ix_preorder_payable(status, payable_from) 로 인덱스만 읽는다. */
    @Query("select p.status as status, count(p) as count from Preorder p group by p.status")
    List<StatusCount> countByStatus();

    interface StatusCount {

        PreorderStatus getStatus();

        long getCount();
    }
}
