package com.grandis.nova.catalog.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByCode(String code);

    List<Category> findAllByOrderById();

    List<Category> findByParentIdIsNullOrderById();

    List<Category> findByParentIdOrderById(Long parentId);
}
