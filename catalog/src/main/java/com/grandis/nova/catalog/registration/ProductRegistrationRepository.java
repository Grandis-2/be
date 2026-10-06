package com.grandis.nova.catalog.registration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductRegistrationRepository extends JpaRepository<ProductRegistration, Long> {

    Optional<ProductRegistration> findByIdempotencyKey(String idempotencyKey);
}
