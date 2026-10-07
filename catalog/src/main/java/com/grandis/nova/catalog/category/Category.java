package com.grandis.nova.catalog.category;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 카테고리. 2단계 — 상위 아래 하위 하나까지. 행은 마이그레이션이 넣고 관리자 CRUD 는 없다(이름은 프론트와 맞춘 뒤 넣는다).
 *
 * 상품은 상위 또는 하위 하나에 배정한다. 상위 목록은 상위 직접 배정 상품과 하위 배정 상품을 함께 보인다.
 */
@Entity
@Table(name = "categories")
public class Category extends BaseEntity {

    /** 상위 카테고리. null 이면 상위다. */
    private UUID parentId;

    @Column(nullable = false, length = 60)
    private String name;

    /** 표시 순서. 작을수록 앞. 같으면 생성 순(created_at, 같으면 id)이다. */
    @Column(nullable = false)
    private int sortOrder;

    protected Category() {
    }

    public boolean isTopLevel() {
        return parentId == null;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getName() {
        return name;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
