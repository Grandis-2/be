package com.grandis.nova.common;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/**
 * 자기 id 를 갖는 엔티티의 부모. id 는 UUID v7 이고 persist 할 때 Hibernate 가 채운다.
 *
 * 앞 48비트가 밀리초 시각이라 BINARY(16) 기본 키 끝에 붙는다. 같은 밀리초 안의 순서는 인스턴스 사이에 보장되지 않으므로
 * id 순서를 생성 순서로 쓰지 않는다.
 */
@MappedSuperclass
public abstract class BaseEntity extends BaseTimeEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    public UUID getId() {
        return id;
    }
}
