package com.grandis.nova.catalog.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

    List<ProductOption> findByProductIdOrderById(Long productId);
}
