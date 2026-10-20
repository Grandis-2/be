package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.ProductListing;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatch;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchPlan;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchRepository;
import com.grandis.nova.preorder.web.ValidationFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 회차 · 차수를 바꾸는 트랜잭션. 모두 회차 행을 잠근 채 한다 —
 * 접수도 같은 행을 잠그므로 "오픈 전인가" 판정과 접수가 경합하지 않는다. 일정이 바뀌면 같은 트랜잭션에서 회차 변경 이벤트를 적는다.
 *
 * 외부 호출(catalog)은 여기 없다. 상품 확인은 트랜잭션 밖에서 끝내고 들어온다.
 */
@Component
class PreorderCampaignWriter {

    private static final Logger log = LoggerFactory.getLogger(PreorderCampaignWriter.class);

    private final PreorderCampaignRepository campaigns;
    private final ShipmentBatchRepository batches;
    private final CampaignChangePublisher changePublisher;
    private final CampaignProperties properties;
    private final Clock clock;

    PreorderCampaignWriter(PreorderCampaignRepository campaigns, ShipmentBatchRepository batches,
                           CampaignChangePublisher changePublisher, CampaignProperties properties, Clock clock) {
        this.campaigns = campaigns;
        this.batches = batches;
        this.changePublisher = changePublisher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 없으면 만들고 있으면 바꾼다. 오픈 뒤에는 바꾸지 않는다.
     *
     * @param listing 새로 만들 때만 쓰는 catalog 상품 값. 번호는 0 이어서 catalog 이벤트가 이긴다
     */
    @Transactional
    PreorderCampaign upsert(UUID productId, Instant opensAt, Instant closesAt, ProductListing listing) {
        return campaigns.findForUpdate(productId)
                .map(campaign -> reschedule(campaign, opensAt, closesAt))
                .orElseGet(() -> create(productId, opensAt, closesAt, listing));
    }

    private PreorderCampaign create(UUID productId, Instant opensAt, Instant closesAt, ProductListing listing) {
        requireLeadTime(opensAt);
        PreorderCampaign campaign = campaigns.save(new PreorderCampaign(productId, opensAt, closesAt, listing));
        changePublisher.publish(campaign, CampaignChange.CREATED);
        return campaign;
    }

    /** 오픈 전 전체 교체. 순번이 나간 뒤에 구간을 바꾸면 그 순번의 배송 차수가 달라진다. */
    @Transactional
    List<ShipmentBatch> replaceBatches(UUID productId, ShipmentBatchPlan plan) {
        PreorderCampaign campaign = campaigns.findForUpdate(productId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
        requireBeforeOpen(campaign);
        batches.deleteByProductId(productId);
        batches.flush();
        List<ShipmentBatch> saved = batches.saveAll(plan.toBatches(productId));
        changePublisher.batchesReplaced(productId);
        return saved;
    }

    /**
     * 접수 중인 회차를 지금 마감한다. 기존 예약은 그대로이고 새 접수만 막힌다 — 접수도 이 행을 잠그므로 마감 뒤의 접수는 거절된다.
     * 이미 마감이면 그대로 돌려준다(이벤트 없음). 다시 여는 것은 {@link #reopen} 이다.
     */
    @Transactional
    PreorderCampaign closeEarly(UUID productId, String closedBy) {
        PreorderCampaign campaign = campaigns.findForUpdate(productId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
        Instant now = clock.instant();
        if (!campaign.isOpened(now)) {
            throw new BusinessException(PreorderErrorCode.SALE_NOT_OPEN);
        }
        if (campaign.closeNow(now)) {
            changePublisher.publish(campaign, CampaignChange.CLOSED);
            log.info("접수 조기 마감 productId={} closedBy={} closesAt={}", productId, closedBy, now);
        }
        return campaign;
    }

    /**
     * 마감된 회차를 새 마감 시각까지 다시 연다. 오픈 시각 · 차수 · 발급한 순번은 그대로라 새 접수는 순번을 이어 받는다.
     * 접수 · 마감도 이 행을 잠그므로 판정과 경합하지 않는다.
     */
    @Transactional
    PreorderCampaign reopen(UUID productId, Instant closesAt, String reopenedBy) {
        PreorderCampaign campaign = campaigns.findForUpdate(productId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
        Instant now = clock.instant();
        if (!campaign.isOpened(now)) {
            throw new BusinessException(PreorderErrorCode.SALE_NOT_OPEN);
        }
        if (campaign.isAccepting(now)) {
            throw new BusinessException(PreorderErrorCode.CAMPAIGN_NOT_REOPENABLE, Map.of("reason", "ACCEPTING"));
        }
        // 한 번도 열린 적 없는 회차를 지금 열면 원래 오픈 시각을 건너뛰고 바로 접수한다
        if (campaign.wasClosedBeforeOpen()) {
            throw new BusinessException(PreorderErrorCode.CAMPAIGN_NOT_REOPENABLE, Map.of("reason", "NEVER_OPENED"));
        }
        // 저장 정밀도로 맞춘 값으로 판정 · 저장한다 — 보정 마감이 이 값으로 "그 사이 바뀌지 않았는가" 를 본다
        Instant stored = closesAt.truncatedTo(ChronoUnit.MICROS);
        if (!stored.isAfter(now)) {
            throw ValidationFailures.of("closesAt", "현재 시각보다 뒤여야 합니다.");
        }
        Instant closedAt = campaign.getClosesAt();
        campaign.reopen(stored);
        changePublisher.publish(campaign, CampaignChange.REOPENED);
        log.info("마감한 접수를 다시 열었다 productId={} reopenedBy={} closedAt={} closesAt={}", productId, reopenedBy,
                closedAt, stored);
        return campaign;
    }

    /**
     * 다시 연 회차를 지금 마감한다. 마감 시각이 다시 열 때의 값과 다르면 그 뒤 다른 관리자가 마감 · 다시 열기를 한 것이라 그대로 둔다.
     * 일정 번호로 가르지 않는다 — 공개 여부가 바뀌어도 번호가 오른다.
     */
    @Transactional
    void closeReopened(UUID productId, Instant reopenedClosesAt, String closedBy) {
        campaigns.findForUpdate(productId)
                .filter(campaign -> campaign.getClosesAt().equals(reopenedClosesAt))
                .filter(campaign -> campaign.closeNow(clock.instant()))
                .ifPresent(campaign -> {
                    changePublisher.publish(campaign, CampaignChange.CLOSED);
                    log.warn("다시 연 회차를 되돌려 마감했다 productId={} closedBy={}", productId, closedBy);
                });
    }

    private PreorderCampaign reschedule(PreorderCampaign campaign, Instant opensAt, Instant closesAt) {
        requireBeforeOpen(campaign);
        // 같은 일정으로 다시 저장하면 바뀐 것이 없으므로 준비 시간 검사도 이벤트도 없다
        if (campaign.hasSchedule(opensAt, closesAt)) {
            return campaign;
        }
        requireLeadTime(opensAt);
        campaign.reschedule(opensAt, closesAt);
        changePublisher.publish(campaign, CampaignChange.RESCHEDULED);
        return campaign;
    }

    /**
     * 오픈 시각은 최소 준비 시간 뒤여야 한다. 지난 시각으로 열면 배송 차수를 더 넣을 수 없고(오픈 뒤 변경 금지),
     * 너무 가까우면 대기열이 일정 이벤트를 받기 전에 열릴 수 있다.
     */
    private void requireLeadTime(Instant opensAt) {
        Duration minLeadTime = properties.minLeadTime();
        if (opensAt.isBefore(clock.instant().plus(minLeadTime))) {
            throw ValidationFailures.of("opensAt", "현재 시각부터 %d분 뒤 이후여야 합니다.".formatted(minLeadTime.toMinutes()));
        }
    }

    private void requireBeforeOpen(PreorderCampaign campaign) {
        if (campaign.isOpened(clock.instant())) {
            throw new BusinessException(PreorderErrorCode.PRODUCT_ALREADY_OPEN);
        }
    }
}
