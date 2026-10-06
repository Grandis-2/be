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

/**
 * 관리자의 "한 번 등록" 기록 — 멱등 키와 상품의 대응. 상품당 한 행이고 상품 id 가 곧 PK 다.
 *
 * 등록은 catalog 저장 한 트랜잭션에서 끝난다. preorder(회차 · 차수) · order(초기 재고)에 넣을 값은 같은 트랜잭션에서
 * 아웃박스 이벤트로 적고, 받는 쪽이 자기 표에 만든다. 그래서 이 기록에는 단계 · 완료 · 리스 칸이 없다(2026-10-02 이벤트 방식 전환).
 * 등록이 끝났는지(판매 방식별 준비)는 다른 서비스의 행이 있는지로 읽는다 — {@link com.grandis.nova.catalog.listing.ProductListingQueryRepository#isReady}.
 * 같은 키로 다시 오면 본문 내용은 대조하지 않는다 — 다른 본문이 오는 것은 프론트 버그일 때뿐이다(2026-09-30 결정).
 *
 * 키(상품 id)를 직접 할당하므로 {@link Persistable} 로 새 객체임을 알린다. 안 그러면 Spring Data 의 save() 가 merge 를 타서
 * 이미 있는 등록 위에 두 번째 {@link #start} 를 저장해도 오류 없이 덮인다(실측).
 * 같은 이유로 새로 만든 인스턴스를 delete() 에 넘기면 "새 객체는 지울 게 없다" 며 아무것도 안 지운다(실측) —
 * 삭제는 deleteById 나 조회한 인스턴스로 한다.
 */
@Entity
@Table(name = "product_registrations")
public class ProductRegistration extends BaseEntity implements Persistable<Long> {

    @Id
    private Long productId;

    @Transient
    private boolean isNew = true;

    @Column(nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    protected ProductRegistration() {
    }

    private ProductRegistration(Long productId, String idempotencyKey) {
        this.productId = productId;
        this.idempotencyKey = idempotencyKey;
    }

    /** catalog 저장과 같은 트랜잭션에서 만든다. */
    public static ProductRegistration start(Long productId, String idempotencyKey) {
        return new ProductRegistration(productId, idempotencyKey);
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
}
