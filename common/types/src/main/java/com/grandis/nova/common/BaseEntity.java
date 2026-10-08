package com.grandis.nova.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * id 와 생성·변경 시각을 공통으로 갖는 엔티티의 부모.
 *
 * id 는 UUID v7 이고 persist 할 때 Hibernate 가 채운다. 앞 48비트가 밀리초 시각이라 BINARY(16) 기본 키 끝에 붙는다.
 * 같은 밀리초 안의 순서는 인스턴스 사이에 보장되지 않으므로 id 순서를 생성 순서로 쓰지 않는다.
 *
 * 시각은 ERD 가 UTC datetime(6) 으로 정했으므로 Instant 다. 쓰는 앱은 Auditing 을 켜야 한다 —
 * common:jpa 를 의존하거나(저장 해상도 시계로 켜진다) @EnableJpaAuditing 을 직접 켠다. 둘 다 하면 기동이 실패하고,
 * 안 켜면 NOT NULL 제약에 걸려서야 드러난다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
