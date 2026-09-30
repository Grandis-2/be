package com.grandis.nova.catalog.option;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductOptionAxisRepository extends JpaRepository<ProductOptionAxis, Long> {

    List<ProductOptionAxis> findByProductIdOrderByPosition(Long productId);
}
