package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.CachedCampaign;
import com.grandis.nova.preorder.campaign.ProductStatus;
import com.grandis.nova.preorder.campaign.domain.OpenAlertStore;
import com.grandis.nova.preorder.campaign.domain.OpenAlertStore.MyOpenAlert;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 회원의 오픈 알림 신청. 판정은 회차 행의 상품 사본(공개 여부 · 판매 상태)으로 한다 — catalog 를 부르지 않는다.
 * 보내는 일은 {@link OpenAlertNotifier} 가 한다.
 */
@Service
public class OpenAlertService {

    /** 내 신청 목록의 상한. 오픈 전 회차만 보이므로 이보다 많을 일이 드물다. */
    static final int MY_ALERTS_LIMIT = 100;

    private final PreorderCampaignRepository campaigns;
    private final CampaignCache cache;
    private final OpenAlertStore alerts;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    OpenAlertService(PreorderCampaignRepository campaigns, CampaignCache cache, OpenAlertStore alerts,
                     TransactionTemplate transactionTemplate, Clock clock) {
        this.campaigns = campaigns;
        this.cache = cache;
        this.alerts = alerts;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 신청한다. 이미 신청했으면 그대로다. 회차 행을 공유 잠금한 뒤 "아직 표시 전이고 오픈 전인가" 를 본다 — 표시(회차 행 UPDATE)는
     * 이 잠금이 풀리길 기다리므로, 여기서 넣은 신청은 표시보다 먼저 커밋돼 표시 뒤에 시작하는 발송이 읽는다. 잠금을 얻은 뒤에 시각을 다시
     * 읽고, 표시가 이미 됐으면 막는다 — 표시 뒤의 신청은 발송에 들지 못한다.
     *
     * 그 앞에 회차 캐시로 "없음 · 비공개 · 이미 열림" 을 잠그지 않고 거절한다 — 공유 잠금은 접수의 배타 잠금 뒤에 줄 서므로, 오픈 직후
     * 어차피 409 인 신청이 그동안 커넥션을 쥐지 않게. 오픈 알림 표시는 캐시에 없어 그 판정은 잠금 뒤에만 한다.
     *
     * @throws BusinessException PRODUCT_NOT_FOUND — 회차가 없거나 비공개, PRODUCT_ALREADY_OPEN — 이미 열렸거나 오픈 알림을 보냈다,
     *                           PRODUCT_PAUSED — 판매 중지
     */
    public void subscribe(UUID customerId, UUID productId) {
        CachedCampaign cached = cache.find(productId)
                .filter(CachedCampaign::visible)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
        if (cached.opened(clock.instant())) {
            throw alreadyOpen();
        }
        transactionTemplate.executeWithoutResult(status -> subscribeLocked(customerId, productId));
    }

    private void subscribeLocked(UUID customerId, UUID productId) {
        PreorderCampaign campaign = campaigns.findForShare(productId)
                .filter(PreorderCampaign::isVisible)   // 비공개 상품은 있는지도 알리지 않는다
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
        Instant now = clock.instant();
        if (campaign.getOpenNotifiedAt() != null || campaign.isOpened(now)) {
            throw alreadyOpen();
        }
        if (campaign.getProductStatus() == ProductStatus.PAUSED) {
            throw new BusinessException(PreorderErrorCode.PRODUCT_PAUSED);
        }
        alerts.subscribe(productId, customerId, now);
    }

    private static BusinessException alreadyOpen() {
        return new BusinessException(PreorderErrorCode.PRODUCT_ALREADY_OPEN, "이미 오픈한 사전예약은 알림을 신청할 수 없습니다.");
    }

    /** 신청을 끈다. 신청이 없거나 회차가 없어도 그대로 끝난다. */
    @Transactional
    public void unsubscribe(UUID customerId, UUID productId) {
        alerts.unsubscribe(productId, customerId);
    }

    @Transactional(readOnly = true)
    public boolean isSubscribed(UUID customerId, UUID productId) {
        return alerts.exists(productId, customerId);
    }

    /** 내가 신청한 오픈 전 공개 회차. 오픈이 이른 순으로 {@value #MY_ALERTS_LIMIT}개까지. */
    @Transactional(readOnly = true)
    public List<MyOpenAlert> findMine(UUID customerId) {
        return alerts.findMine(customerId, clock.instant(), MY_ALERTS_LIMIT);
    }
}
