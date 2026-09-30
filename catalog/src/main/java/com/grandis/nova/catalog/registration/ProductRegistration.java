package com.grandis.nova.catalog.registration;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * 관리자의 "한 번 등록" 기록. 상품당 한 행이고 상품 id 가 곧 PK 다.
 *
 * 등록은 catalog 저장 → preorder(회차 · 차수) 또는 order(재고) 호출 → 완료의 여러 단계다.
 * 단계마다 완료 시각을 두고 FAILED 같은 상태값은 두지 않는다 — 실패는 "완료 시각이 비어 있고 lastError 가 있다" 로 읽고
 * 같은 Idempotency-Key 로 재개한다. 같은 키로 다시 오면 본문 내용은 대조하지 않는다 — 다른 본문이 오는 것은 프론트 버그일 때뿐이다(2026-09-30 결정).
 * completedAt 이 있어야 노출 · 거래 조건의 앞 조건이 참이 된다. blockedReason 이 있으면 자동 재개가 없다.
 * 리스(leaseToken · leaseExpiresAt)는 동시 재개 제어다 — 단계 기록은 자기 리스일 때만 반영한다. 둘은 같이 있거나 같이 없다(DB CHECK).
 * lastError 는 500자다. 쓰는 쪽이 코드포인트 기준으로 자른다 — 넘기면 오류를 기록하는 UPDATE 가 실패해 오류가 사라진다.
 *
 * 키(상품 id)를 직접 할당하므로 {@link Persistable} 로 새 객체임을 알린다. 안 그러면 Spring Data 의 save() 가 merge 를 타서
 * 이미 완료된 등록 위에 두 번째 {@link #start} 를 저장해도 오류 없이 완료 시각 · 단계 시각이 null 로 덮인다(실측).
 * 같은 이유로 새로 만든 인스턴스를 delete() 에 넘기면 "새 객체는 지울 게 없다" 며 아무것도 안 지운다(실측) —
 * 삭제는 deleteById 나 조회한 인스턴스로 한다.
 */
@Entity
@Table(name = "product_registrations")
public class ProductRegistration extends BaseEntity implements Persistable<Long> {

    public static final int LAST_ERROR_LENGTH = 500;

    @Id
    private Long productId;

    @Transient
    private boolean isNew = true;

    @Column(nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private boolean requestedVisible;

    private Instant campaignSetAt;

    private Instant batchesSetAt;

    private Instant stockSetAt;

    private Instant completedAt;

    @Column(length = 100)
    private String blockedReason;

    @Column(length = LAST_ERROR_LENGTH)
    private String lastError;

    @Column(length = 64)
    private String leaseToken;

    private Instant leaseExpiresAt;

    protected ProductRegistration() {
    }

    private ProductRegistration(Long productId, String idempotencyKey, boolean requestedVisible) {
        this.productId = productId;
        this.idempotencyKey = idempotencyKey;
        this.requestedVisible = requestedVisible;
    }

    /** catalog 저장 단계가 끝난 직후의 기록. 나머지 단계 시각은 비어 있다. */
    public static ProductRegistration start(Long productId, String idempotencyKey, boolean requestedVisible) {
        return new ProductRegistration(productId, idempotencyKey, requestedVisible);
    }

    public boolean isCompleted() {
        return completedAt != null;
    }

    public boolean isBlocked() {
        return blockedReason != null;
    }

    @Override
    public Long getId() {
        return productId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public Long getProductId() {
        return productId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public boolean isRequestedVisible() {
        return requestedVisible;
    }

    public Instant getCampaignSetAt() {
        return campaignSetAt;
    }

    public Instant getBatchesSetAt() {
        return batchesSetAt;
    }

    public Instant getStockSetAt() {
        return stockSetAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public String getLastError() {
        return lastError;
    }

    public String getLeaseToken() {
        return leaseToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }
}
