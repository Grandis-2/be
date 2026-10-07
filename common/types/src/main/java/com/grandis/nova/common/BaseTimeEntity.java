package com.grandis.nova.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 생성·변경 시각을 공통으로 갖는 엔티티의 부모. 자기 id 가 있는 엔티티는 {@link BaseEntity} 를 상속하고,
 * 기본 키가 다른 표의 id 인 엔티티(회차 · 상품 등록처럼 상품과 1:1)만 이것을 직접 상속한다.
 *
 * 시각은 ERD 가 UTC datetime(6) 으로 정했으므로 Instant 다. 쓰는 앱은 Auditing 을 켜야 한다 —
 * common:jpa 를 의존하거나(저장 해상도 시계로 켜진다) @EnableJpaAuditing 을 직접 켠다. 둘 다 하면 기동이 실패하고,
 * 안 켜면 NOT NULL 제약에 걸려서야 드러난다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
