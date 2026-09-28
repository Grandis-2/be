package com.grandis.nova.preorder.campaign;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface PreorderCampaignRepository extends JpaRepository<PreorderCampaign, Long> {

    /**
     * 회차 행을 SELECT … FOR UPDATE 로 잠근다. 같은 회차의 접수는 여기서 한 줄로 선다.
     * 트랜잭션이 끝날 때까지 잠금이 유지되므로 트랜잭션 안에서만 부른다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PreorderCampaign c where c.productId = :productId")
    Optional<PreorderCampaign> findForUpdate(@Param("productId") Long productId);
}
