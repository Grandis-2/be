package com.grandis.nova.preorder.campaign.domain;

import com.grandis.nova.preorder.campaign.CampaignSchedule;
import com.grandis.nova.preorder.campaign.ProductListing;
import com.grandis.nova.preorder.campaign.ProductStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 사전예약 회차. 사전예약 상품당 1행이고 상품 id 가 곧 PK 다(자기 id 를 만들지 않는다). 그래서 BaseEntity 대신
 * 생성 · 변경 시각만 직접 둔다.
 *
 * 접수는 이 행을 잠근다({@link PreorderCampaignRepository#findForUpdate}).
 * 순번 카운터가 모집 일정과 같은 행에 있어서 접수 트랜잭션이 한 번만 잠그면 되고,
 * 순번의 유일성은 이 잠금에서 나온다. 상품 정보 수정은 products 를 바꾸므로 접수와 서로 막지 않는다.
 */
@Entity
@Table(name = "preorder_campaigns")
@EntityListeners(AuditingEntityListener.class)
public class PreorderCampaign {

    private static final Duration CLOSED_BEFORE_OPEN = Duration.ofMillis(1);

    /** 새 회차의 일정 번호. 0 은 번호를 매기기 전에 만든 회차다. */
    static final long FIRST_SCHEDULE_VERSION = 1;

    /** 첫 예약이 받는 순번. */
    static final long FIRST_QUEUE_POSITION = 1;

    @Id
    private UUID productId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

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

    /**
     * catalog 상품명 · 판매 상태 · 공개 여부의 변경 번호(products.listing_version). 이보다 큰 번호의 값만 반영한다 — 사본이 비었으면(상품명 null)
     * 같은 번호도 받는다(0 = catalog 값을 받기 전).
     * 칸 이름은 공개 여부만 받던 때의 것이다 — 이름을 바꾸면 마이그레이션과 배포 사이의 구버전이 회차를 못 읽는다.
     */
    @Column(name = "visibility_version", nullable = false)
    private long listingVersion;

    /** catalog 상품명 사본. 오픈 알림 문구에 쓴다. null = catalog 값을 받기 전. */
    @Column(length = 100)
    private String productTitle;

    /** catalog 판매 상태 사본. 판매 중지면 오픈 알림을 보내지 않는다. null = catalog 값을 받기 전. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ProductStatus productStatus;

    @Column(nullable = false)
    private long nextQueuePosition;

    private Instant openNotifiedAt;

    protected PreorderCampaign() {
    }

    /** 사전예약 상품에 회차를 연다. 순번은 1번부터 시작한다. */
    public PreorderCampaign(UUID productId, Instant opensAt, Instant closesAt, ProductListing listing) {
        this.productId = productId;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
        this.visible = listing.visible();
        this.listingVersion = listing.version();
        this.productTitle = listing.title();
        this.productStatus = listing.status();
        this.scheduleVersion = FIRST_SCHEDULE_VERSION;
        this.nextQueuePosition = FIRST_QUEUE_POSITION;
    }

    /** 지금 판매 상태. */
    public PreorderSaleStatus saleStatus(Instant now) {
        return PreorderSaleStatus.of(opensAt, closesAt, now);
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
     * catalog 상품 값의 사본을 덮는다. 번호가 지금보다 클 때만 셋을 함께 쓴다 — 표준 큐라 옛 값이 늦게 올 수 있다.
     * 번호가 같아도 사본이 비어 있으면(상품명 null — 사본 칸이 생기기 전의 회차) 쓴다. catalog 는 셋이 바뀔 때만 번호를 올리므로
     * 같은 번호의 메시지는 모두 같은 값을 싣는다 — 그래서 번호 0 인 기존 상품도 옵션 · 가격만 고친 변경으로 사본이 찬다.
     * 싣지 않은 칸(null)은 그대로 둔다. 공개 여부가 실제로 바뀌면 일정 번호도 올린다 — 대기열은 일정 번호로 새 값을 판정한다.
     * 상품명 · 판매 상태는 대기열이 보지 않아 일정 번호와 무관하다. 반드시 잠근 행에서 부른다.
     *
     * @return 공개 여부가 바뀌었으면 true(회차 변경 이벤트를 낼 것)
     */
    public boolean applyListing(ProductListing listing) {
        boolean empty = this.productTitle == null;
        if (listing.version() < this.listingVersion || (listing.version() == this.listingVersion && !empty)) {
            return false;
        }
        this.listingVersion = listing.version();
        if (listing.title() != null) {
            this.productTitle = listing.title();
        }
        if (listing.status() != null) {
            this.productStatus = listing.status();
        }
        boolean visible = listing.visible();
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

    /** 마감된 회차를 새 마감 시각까지 다시 연다(일정 번호가 오른다). 판정은 호출하는 쪽이 잠근 행에서 한다. */
    public void reopen(Instant closesAt) {
        this.closesAt = closesAt;
        this.scheduleVersion++;
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

    public UUID getProductId() {
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

    public long getListingVersion() {
        return listingVersion;
    }

    public String getProductTitle() {
        return productTitle;
    }

    public ProductStatus getProductStatus() {
        return productStatus;
    }

    public long getNextQueuePosition() {
        return nextQueuePosition;
    }

    public Instant getOpenNotifiedAt() {
        return openNotifiedAt;
    }
}
