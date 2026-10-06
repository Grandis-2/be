package com.grandis.nova.preorder.campaign.domain;

import com.grandis.nova.common.BaseEntity;
import com.grandis.nova.preorder.campaign.CampaignSchedule;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

/**
 * 사전예약 회차. 사전예약 상품당 1행이고 상품 id 가 곧 PK 다.
 *
 * 접수는 이 행을 잠근다({@link PreorderCampaignRepository#findForUpdate}).
 * 순번 카운터가 모집 일정과 같은 행에 있어서 접수 트랜잭션이 한 번만 잠그면 되고,
 * 순번의 유일성은 이 잠금에서 나온다. 상품 정보 수정은 products 를 바꾸므로 접수와 서로 막지 않는다.
 */
@Entity
@Table(name = "preorder_campaigns")
public class PreorderCampaign extends BaseEntity {

    private static final Duration CLOSED_BEFORE_OPEN = Duration.ofMillis(1);

    /** 새 회차의 일정 번호. 0 은 번호를 매기기 전에 만든 회차다. */
    static final long FIRST_SCHEDULE_VERSION = 1;

    /** 첫 예약이 받는 순번. */
    static final long FIRST_QUEUE_POSITION = 1;

    @Id
    private Long productId;

    @Column(nullable = false)
    private Instant opensAt;

    @Column(nullable = false)
    private Instant closesAt;

    /** 일정이 바뀔 때마다 오르는 번호. 순번 발급으로는 오르지 않아 @Version 으로 두지 않는다. */
    @Column(nullable = false)
    private long scheduleVersion;

    /** 회원에게 공개한 상품인가. 원장은 catalog 이고, 대기열이 비공개 회차의 줄을 멈추도록 회차 이벤트에 싣는다. */
    @Column(nullable = false)
    private boolean visible;

    /** catalog 가 공개 여부를 바꿀 때마다 올리는 번호. 이보다 큰 번호의 값만 반영한다(0 = catalog 값을 받기 전). */
    @Column(nullable = false)
    private long visibilityVersion;

    @Column(nullable = false)
    private long nextQueuePosition;

    private Instant openNotifiedAt;

    protected PreorderCampaign() {
    }

    /** 사전예약 상품에 회차를 연다. 순번은 1번부터 시작한다. */
    public PreorderCampaign(Long productId, Instant opensAt, Instant closesAt, boolean visible,
                            long visibilityVersion) {
        this.productId = productId;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
        this.visible = visible;
        this.visibilityVersion = visibilityVersion;
        this.scheduleVersion = FIRST_SCHEDULE_VERSION;
        this.nextQueuePosition = FIRST_QUEUE_POSITION;
    }

    /** 지금 판매 상태. */
    public PreorderSaleStatus saleStatus(Instant now) {
        if (now.isBefore(opensAt)) {
            return PreorderSaleStatus.BEFORE_OPEN;
        }
        return isAccepting(now) ? PreorderSaleStatus.OPEN : PreorderSaleStatus.CLOSED;
    }

    /** 지금 일정이 이것과 같은가. */
    public boolean hasSchedule(Instant opensAt, Instant closesAt) {
        return this.opensAt.equals(opensAt) && this.closesAt.equals(closesAt);
    }

    /** 일정 변경(일정 번호가 오른다). 오픈 뒤에는 부르지 않는다 — 판정은 호출하는 쪽이 회차 행을 잠근 채 한다. */
    public void reschedule(Instant opensAt, Instant closesAt) {
        this.opensAt = opensAt;
        this.closesAt = closesAt;
        this.scheduleVersion++;
    }

    /**
     * catalog 의 공개 여부를 반영한다. 번호가 지금보다 클 때만 쓰고, 값이 실제로 바뀌면 일정 번호도 올린다 —
     * 대기열은 일정 번호로 새 값을 판정한다. 반드시 잠근 행에서 부른다.
     *
     * @return 공개 여부가 바뀌었으면 true(회차 변경 이벤트를 낼 것)
     */
    public boolean applyVisibility(boolean visible, long visibilityVersion) {
        if (visibilityVersion <= this.visibilityVersion) {
            return false;
        }
        this.visibilityVersion = visibilityVersion;
        if (this.visible == visible) {
            return false;
        }
        this.visible = visible;
        this.scheduleVersion++;
        return true;
    }

    /** 지금까지 발급한 순번 수(취소 행 포함). */
    public long issuedCount() {
        return nextQueuePosition - FIRST_QUEUE_POSITION;
    }

    /**
     * 판매 중지로 지금 마감한다. 오픈 전이면 마감 > 오픈 제약을 지키려고 기간 전체를 지난 것으로 둔다.
     *
     * @return 마감했으면 true(일정 번호도 오른다). 이미 마감이 지났으면 false
     */
    public boolean closeNow(Instant now) {
        if (!now.isBefore(closesAt)) {
            return false;
        }
        if (!now.isAfter(opensAt)) {
            this.opensAt = now.minus(CLOSED_BEFORE_OPEN);
        }
        this.closesAt = now;
        this.scheduleVersion++;
        return true;
    }

    /** 오픈 시각 이상, 마감 시각 미만일 때 접수를 받는다. */
    public boolean isAccepting(Instant now) {
        return !now.isBefore(opensAt) && now.isBefore(closesAt);
    }

    /** 오픈 뒤에는 일정 · 배송 차수를 바꿀 수 없다. */
    public boolean isOpened(Instant now) {
        return !now.isBefore(opensAt);
    }

    /**
     * 다음 순번을 발급하고 카운터를 올린다. 반드시 {@link PreorderCampaignRepository#findForUpdate} 로
     * 잠근 행에서 부른다 — 잠그지 않은 행에서 부르면 두 트랜잭션이 같은 번호를 낸다.
     */
    public long issueQueuePosition() {
        long position = nextQueuePosition;
        nextQueuePosition = position + 1;
        return position;
    }

    /** 다른 모듈에 넘기는 모집 일정. */
    public CampaignSchedule toSchedule() {
        return new CampaignSchedule(getProductId(), getOpensAt(), getClosesAt());
    }

    public Long getProductId() {
        return productId;
    }

    public Instant getOpensAt() {
        return opensAt;
    }

    public Instant getClosesAt() {
        return closesAt;
    }

    public long getScheduleVersion() {
        return scheduleVersion;
    }

    public boolean isVisible() {
        return visible;
    }

    public long getVisibilityVersion() {
        return visibilityVersion;
    }

    public long getNextQueuePosition() {
        return nextQueuePosition;
    }

    public Instant getOpenNotifiedAt() {
        return openNotifiedAt;
    }
}
