package com.grandis.nova.catalog.category;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 카테고리. 2단계 — 상위 아래 하위 하나까지. 행은 마이그레이션이 넣고 관리자 CRUD 는 없다(이름은 프론트와 맞춘 뒤 넣는다).
 *
 * 상품은 상위 또는 하위 하나에 배정한다. 상위 목록은 상위 직접 배정 상품과 하위 배정 상품을 함께 보인다.
 * option_filter_definitions 는 옵션 축 · 값 표로 대체돼 폐기 예정이라 매핑하지 않는다.
 */
@Entity
@Table(name = "categories")
public class Category extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 상위 카테고리. null 이면 상위다. */
    private Long parentId;

    @Column(nullable = false, updatable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 60)
    private String name;

    protected Category() {
    }

    public boolean isTopLevel() {
        return parentId == null;
    }

    public Long getId() {
        return id;
    }

    public Long getParentId() {
        return parentId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
