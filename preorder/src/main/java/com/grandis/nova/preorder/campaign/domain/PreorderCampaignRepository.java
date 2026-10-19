package com.grandis.nova.preorder.campaign.domain;

import com.grandis.nova.preorder.campaign.ProductStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    /** 마감 전이고 horizon 까지 열리는 회차(이미 열린 것 포함). 오픈이 이른 순. 잠그지 않는다. */
    @Query("""
            select c from PreorderCampaign c
             where c.closesAt > :now and c.opensAt <= :horizon
             order by c.opensAt, c.productId""")
    List<PreorderCampaign> findOpenBy(@Param("now") Instant now, @Param("horizon") Instant horizon, Limit limit);

    /**
     * 회차 행을 SELECT … FOR SHARE 로 잠근다. 오픈 알림 신청이 "아직 표시 전인가" 를 본 뒤 신청 행을 커밋할 때까지 오픈 표시({@link #markOpenNotified})가
     * 기다린다. 신청끼리는 서로 막지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from PreorderCampaign c where c.productId = :productId")
    Optional<PreorderCampaign> findForShare(@Param("productId") UUID productId);

    /**
     * 오픈 알림 창 안의 보낼 회차 — 열렸고(since 뒤 · 마감 전) 아직 표시하지 않았고 공개 · 판매 중지 아님. 오래된 오픈부터. 잠그지 않는다.
     * 공개 · 판매 조건도 여기서 거른다 — 건너뛸 회차가 상한(limit)을 차지하면 보낼 회차가 다음 주기로 밀린다.
     * 표시({@link #markOpenNotified})가 같은 조건을 한 문장으로 다시 본다.
     */
    @Query("""
            select c from PreorderCampaign c
             where c.openNotifiedAt is null and c.opensAt <= :now and c.opensAt > :since and c.closesAt > :now
               and c.visible = true and (c.productStatus is null or c.productStatus <> :paused)
             order by c.opensAt, c.productId""")
    List<PreorderCampaign> findOpenAlertWindow(@Param("now") Instant now, @Param("since") Instant since,
                                               @Param("paused") ProductStatus paused, Limit limit);

    /** 지금 접수 중인 회차 수 — 오픈 뒤 · 마감 전 · 공개 · 판매 중지 아님. 지표용. */
    @Query("""
            select count(c) from PreorderCampaign c
             where c.opensAt <= :now and c.closesAt > :now
               and c.visible = true and (c.productStatus is null or c.productStatus <> :paused)""")
    long countAccepting(@Param("now") Instant now, @Param("paused") ProductStatus paused);

    /** 오픈 알림 창 안에서 비공개라 표시하지 않은 회차 수. 지표용. */
    @Query("""
            select count(c) from PreorderCampaign c
             where c.openNotifiedAt is null and c.opensAt <= :now and c.opensAt > :since and c.closesAt > :now
               and c.visible = false""")
    long countOpenAlertWindowHidden(@Param("now") Instant now, @Param("since") Instant since);

    /** 오픈 알림 창 안에서 공개지만 판매 중지라 표시하지 않은 회차 수. 지표용. */
    @Query("""
            select count(c) from PreorderCampaign c
             where c.openNotifiedAt is null and c.opensAt <= :now and c.opensAt > :since and c.closesAt > :now
               and c.visible = true and c.productStatus = :paused""")
    long countOpenAlertWindowPaused(@Param("now") Instant now, @Param("since") Instant since,
                                    @Param("paused") ProductStatus paused);

    /** 표시하지 못한 채 오픈 알림 창이 (after, upTo] 사이에 끝난 회차. 잠그지 않는다. */
    @Query("""
            select c from PreorderCampaign c
             where c.openNotifiedAt is null and c.opensAt > :after and c.opensAt <= :upTo
             order by c.opensAt, c.productId""")
    List<PreorderCampaign> findOpenAlertExpired(@Param("after") Instant after, @Param("upTo") Instant upTo);

    /**
     * 오픈 알림 표시. 아직 표시 전이고, 열렸고(since 뒤 · 마감 전), 공개 · 판매 중지 아님일 때만 채운다 — 조건을 한 문장에 두어
     * 여러 인스턴스가 같은 회차를 집어도 한 곳만 1 을 받는다. 판매 상태를 아직 받지 못한 회차(null)는 막지 않는다 — catalog 는 새 상품을
     * 늘 판매 중으로 만들고, 막으면 그 회차의 오픈 알림이 조용히 사라진다.
     * 회차 행 잠금은 트랜잭션 끝까지 가므로 표시 트랜잭션에는 이 문장과 진행 행 넣기만 둔다.
     *
     * @return 채웠으면 1, 아니면 0(이미 보냈거나 · 비공개 · 판매 중지 · 일정이 바뀌었다)
     */
    @Modifying
    @Query("""
            update PreorderCampaign c set c.openNotifiedAt = :now, c.updatedAt = :now
             where c.productId = :productId and c.openNotifiedAt is null
               and c.opensAt <= :now and c.opensAt > :since and c.closesAt > :now
               and c.visible = true and (c.productStatus is null or c.productStatus <> :paused)""")
    int markOpenNotified(@Param("productId") UUID productId, @Param("now") Instant now, @Param("since") Instant since,
                         @Param("paused") ProductStatus paused);
}
