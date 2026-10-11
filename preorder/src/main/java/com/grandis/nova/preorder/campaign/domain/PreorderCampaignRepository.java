package com.grandis.nova.preorder.campaign.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PreorderCampaignRepository extends JpaRepository<PreorderCampaign, UUID> {

    /**
     * 회차 행을 SELECT … FOR UPDATE 로 잠근다. 같은 회차의 접수는 여기서 한 줄로 선다.
     * 트랜잭션이 끝날 때까지 잠금이 유지되므로 트랜잭션 안에서만 부른다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PreorderCampaign c where c.productId = :productId")
    Optional<PreorderCampaign> findForUpdate(@Param("productId") UUID productId);

    /** 마감 전 회차를 상품 id 순으로 afterProductId 다음부터 읽는다(전체 재발행의 한 묶음). 잠그지 않는다. */
    @Query("""
            select c from PreorderCampaign c
             where c.closesAt > :now and c.productId > :afterProductId
             order by c.productId""")
    List<PreorderCampaign> findNotClosedAfter(@Param("now") Instant now, @Param("afterProductId") UUID afterProductId,
                                              Limit limit);
}
