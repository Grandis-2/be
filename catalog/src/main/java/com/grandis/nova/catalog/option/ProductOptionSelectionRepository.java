package com.grandis.nova.catalog.option;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductOptionSelectionRepository extends JpaRepository<ProductOptionSelection, ProductOptionSelectionId> {

    List<ProductOptionSelection> findByProductId(Long productId);
}
