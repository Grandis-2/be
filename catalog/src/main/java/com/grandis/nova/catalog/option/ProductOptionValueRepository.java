package com.grandis.nova.catalog.option;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ProductOptionValueRepository extends JpaRepository<ProductOptionValue, Long> {

    List<ProductOptionValue> findByAxisIdInOrderByAxisIdAscPositionAsc(Collection<Long> axisIds);
}
