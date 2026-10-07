package com.grandis.nova.catalog.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** 표시 순서(sort_order) 오름차순, 같으면 id 오름차순 — 순서 칸이 같은 행끼리도 늘 같은 순서로 나온다. */
    List<Category> findAllByOrderBySortOrderAscIdAsc();
}
